package com.jujin.mjm.cli;

import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.Requirement;
import com.jujin.mjm.resolve.Scope;
import com.jujin.mjm.store.ArtifactStore;
import com.jujin.mjm.store.LockedArtifact;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that turns "this project and its lock" into a classpath. Every
 * command that needs one asks a named {@link Mode}; nothing outside this class
 * assembles entries or joins them.
 *
 * <p>The modes are the shapes that already existed, frozen:
 * <ul>
 *   <li>{@link Mode#COMPILE} — the declared MAIN dependencies only (strict
 *       visibility), no {@code target/classes}; used by {@code build}.</li>
 *   <li>{@link Mode#TEST} — test classes, main classes, every artifact in the
 *       lock, the running mjm classes, then the toolchain jars.</li>
 *   <li>{@link Mode#RUN} — main classes, then every MAIN artifact (dev and
 *       processor skipped).</li>
 *   <li>{@link Mode#EXPORT_MAIN} — main classes plus the declared MAIN jars;
 *       what an IDE should compile against.</li>
 *   <li>{@link Mode#EXPORT_TEST} — test classes, main classes, every artifact
 *       except processors; the IDE's test classpath.</li>
 * </ul>
 *
 * <p><b>Known inconsistency, deliberately unchanged:</b> {@code TEST} includes
 * processor-scope artifacts while {@code EXPORT_TEST} excludes them. Both
 * behaviours predate this class; deciding which is right is its own change
 * (REMINDS.md).
 */
final class ClasspathAssembler {

    enum Mode { COMPILE, TEST, RUN, EXPORT_MAIN, EXPORT_TEST }

    /** Everything a mode might need; each factory fills only its own part. */
    record Input(MjProject project, List<LockedArtifact> locked,
                 Map<String, Path> verifiedJars, List<Path> toolchain,
                 Path runtimeLocation, boolean includeTestOutput) {

        static Input compile(MjProject project, Map<String, Path> verifiedJars) {
            return new Input(project, List.of(), verifiedJars, List.of(), null, false);
        }

        static Input test(List<LockedArtifact> locked, List<Path> toolchain,
                          Path runtimeLocation, boolean includeTestOutput) {
            return new Input(null, locked, Map.of(), toolchain, runtimeLocation,
                    includeTestOutput);
        }

        static Input run(List<LockedArtifact> locked) {
            return new Input(null, locked, Map.of(), List.of(), null, false);
        }

        static Input export(MjProject project, List<LockedArtifact> locked) {
            return new Input(project, locked, Map.of(), List.of(), null, false);
        }
    }

    /** A mode's entries, and the joined form its callers hand to java/javac. */
    record Classpath(List<Path> entries) {

        /** The only place a classpath is joined — callers use this string. */
        String joined() {
            return ClasspathAssembler.join(entries);
        }

        List<String> strings() {
            return entries.stream().map(Path::toString).toList();
        }
    }

    static Classpath of(Mode mode, Path projectDir, ArtifactStore store, Input input) {
        List<Path> entries = new ArrayList<>();
        switch (mode) {
            case COMPILE -> {
                for (Requirement requirement : input.project().requirements()) {
                    Path jar = input.verifiedJars().get(requirement.id());
                    if (jar == null) {
                        throw new ClasspathException("mjm.lock is stale — '" + requirement.id()
                                + "' is declared in mjm.toml but not locked; run mjm lock");
                    }
                    entries.add(jar);
                }
            }
            case TEST -> {
                Path testOutput = projectDir.resolve("target/test-classes");
                if (input.includeTestOutput() && Files.exists(testOutput)) {
                    entries.add(testOutput);
                }
                entries.add(projectDir.resolve("target/classes"));
                for (LockedArtifact artifact : input.locked()) {
                    Path jar = storeJar(artifact, store, true);
                    if (jar != null) {
                        entries.add(jar);
                    }
                }
                entries.add(input.runtimeLocation());
                entries.addAll(input.toolchain());
            }
            case RUN -> {
                entries.add(projectDir.resolve("target/classes"));
                for (LockedArtifact artifact : input.locked()) {
                    if (!artifact.isMain()) {
                        continue;  // [dev-deps] never reaches a production run
                    }
                    entries.add(storeJar(artifact, store, false));
                }
            }
            case EXPORT_MAIN -> {
                entries.add(projectDir.resolve("target/classes"));
                Map<String, LockedArtifact> byId = new LinkedHashMap<>();
                for (LockedArtifact artifact : input.locked()) {
                    byId.put(artifact.id(), artifact);
                }
                for (Requirement requirement : input.project().requirements()) {
                    LockedArtifact artifact = byId.get(requirement.id());
                    if (artifact == null) {
                        throw new ClasspathException("mjm.lock is stale — '"
                                + requirement.id() + "' is declared in mjm.toml but not locked; "
                                + "run mjm lock");
                    }
                    entries.add(storeJar(artifact, store, false));
                }
            }
            case EXPORT_TEST -> {
                entries.add(projectDir.resolve("target/test-classes"));
                entries.add(projectDir.resolve("target/classes"));
                for (LockedArtifact artifact : input.locked()) {
                    if (artifact.scope() == Scope.PROCESSOR) {
                        continue;  // processors are build tools, not an API
                    }
                    entries.add(storeJar(artifact, store, false));
                }
            }
        }
        return new Classpath(List.copyOf(entries));
    }

    /**
     * The one place an artifact becomes a path on disk. A workspace replacing a
     * lock row with a local project will hook in here — nothing else knows how
     * a classpath entry is found.
     *
     * @param tolerateMissing TEST skips jars the store lost (it always did);
     *                        every other mode fails with the next action
     */
    private static Path storeJar(LockedArtifact artifact, ArtifactStore store,
                                 boolean tolerateMissing) {
        Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
        if (jar == null && !tolerateMissing) {
            throw new ClasspathException(artifact.id() + "@" + artifact.version()
                    + " is not in the store — run mjm lock");
        }
        return jar;
    }

    /** Joining for the one non-classpath path list (the processor path). */
    static String join(List<?> paths) {
        return String.join(File.pathSeparator,
                paths.stream().map(String::valueOf).toList());
    }

    /** A classpath that cannot be assembled, with the sentence the user should see. */
    static final class ClasspathException extends RuntimeException {
        ClasspathException(String message) {
            super(message);
        }
    }

    private ClasspathAssembler() {}
}
