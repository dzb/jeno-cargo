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
 *   <li>{@link Mode#TEST} — test classes, main classes, every non-processor
 *       artifact in the lock, the running mjm classes, then the toolchain
 *       jars.</li>
 *   <li>{@link Mode#RUN} — main classes, then every MAIN artifact (dev and
 *       processor skipped).</li>
 *   <li>{@link Mode#EXPORT_MAIN} — main classes plus the declared MAIN jars;
 *       what an IDE should compile against.</li>
 *   <li>{@link Mode#EXPORT_TEST} — test classes, main classes, every artifact
 *       except processors; the IDE's test classpath.</li>
 * </ul>
 *
 * <p>Workspace lookups go through {@link Substitutions}: a lock row that names
 * a local module becomes that module's {@code target/classes}, and only a row
 * that does not is looked up in the store. The decision lives in one method
 * ({@link #artifactPath}) so every mode — and packaging — cannot disagree.
 *
 * <p>{@code TEST} and {@code EXPORT_TEST} both exclude processor-scope
 * artifacts: processors compile code, they are not something tests link
 * against. {@code mjm test} passes them on javac's {@code -processorpath}
 * instead (REMINDS.md §14).
 */
final class ClasspathAssembler {

    enum Mode { COMPILE, TEST, RUN, EXPORT_MAIN, EXPORT_TEST }

    /** Everything a mode might need; each factory fills only its own part. */
    record Input(MjProject project, List<LockedArtifact> locked,
                 Map<String, Path> verifiedJars, List<Path> toolchain,
                 Path runtimeLocation, boolean includeTestOutput) {

        static Input compile(MjProject project, List<LockedArtifact> locked) {
            return new Input(project, locked, Map.of(), List.of(), null, false);
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
        return of(mode, projectDir, store, Substitutions.forProject(projectDir), input);
    }

    static Classpath of(Mode mode, Path projectDir, ArtifactStore store,
                        Substitutions substitutions, Input input) {
        List<Path> entries = new ArrayList<>();
        switch (mode) {
            case COMPILE -> {
                Map<String, LockedArtifact> byId = new LinkedHashMap<>();
                for (LockedArtifact artifact : input.locked()) {
                    byId.put(artifact.id(), artifact);
                }
                for (Requirement requirement : input.project().requirements()) {
                    LockedArtifact artifact = byId.get(requirement.id());
                    if (artifact == null) {
                        throw new ClasspathException("mjm.lock is stale — '" + requirement.id()
                                + "' is declared in mjm.toml but not locked; run mjm lock");
                    }
                    entries.add(artifactPath(artifact, store, substitutions, false));
                }
            }
            case TEST -> {
                Path testOutput = projectDir.resolve("target/test-classes");
                if (input.includeTestOutput() && Files.exists(testOutput)) {
                    entries.add(testOutput);
                }
                entries.add(projectDir.resolve("target/classes"));
                for (LockedArtifact artifact : input.locked()) {
                    if (artifact.scope() == Scope.PROCESSOR) {
                        continue;  // processors compile code; they are not an API
                    }
                    Path jar = artifactPath(artifact, store, substitutions, true);
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
                    entries.add(artifactPath(artifact, store, substitutions, false));
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
                    entries.add(artifactPath(artifact, store, substitutions, false));
                }
            }
            case EXPORT_TEST -> {
                entries.add(projectDir.resolve("target/test-classes"));
                entries.add(projectDir.resolve("target/classes"));
                for (LockedArtifact artifact : input.locked()) {
                    if (artifact.scope() == Scope.PROCESSOR) {
                        continue;  // processors are build tools, not an API
                    }
                    entries.add(artifactPath(artifact, store, substitutions, false));
                }
            }
        }
        return new Classpath(List.copyOf(entries));
    }

    /**
     * The one place an artifact becomes a path on disk: a workspace module wins,
     * otherwise the store copy. Nothing else knows how a classpath entry is found.
     *
     * @param tolerateMissing TEST skips jars the store lost (it always did);
     *                        every other mode fails with the next action
     */
    static Path artifactPath(LockedArtifact artifact, ArtifactStore store,
                             Substitutions substitutions, boolean tolerateMissing) {
        try {
            return substitutions.pathOf(artifact, store, tolerateMissing);
        } catch (Substitutions.LocalException e) {
            throw new ClasspathException(e.getMessage());
        }
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
