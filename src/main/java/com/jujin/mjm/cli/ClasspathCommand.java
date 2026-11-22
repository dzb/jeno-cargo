package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.project.Requirement;
import com.jujin.mjm.resolve.Scope;
import com.jujin.mjm.store.ArtifactStore;
import com.jujin.mjm.store.LockFileReader;
import com.jujin.mjm.store.LockedArtifact;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code mjm classpath [--test] [--file <path>]} — the classpath an IDE should
 * use for this project, printed on one line (or written to a file).
 *
 * <p>The default is the <em>compile</em> classpath under strict visibility: the
 * project's classes plus the jars it declares directly. Transitive jars are not
 * included, on purpose — an IDE that offers them would compile code that
 * {@code mjm build} rejects, which is the most confusing disagreement a build
 * tool can have with an editor. {@code --test} widens it to the test classpath:
 * test classes, main classes, and the whole closure (main and dev).
 *
 * <p>The jars are the store's content-addressed files — nothing is copied or
 * linked, same as the build's own classpath. The output is for a local IDE and
 * should not be committed.
 */
public final class ClasspathCommand implements Command {

    private static final String CLASSES = "target/classes";
    private static final String TEST_CLASSES = "target/test-classes";

    private final ArtifactStore store;
    private final Path projectDir;

    @Inject
    public ClasspathCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "classpath";
    }

    @Override
    public String usage() {
        return "[--test] [--file <path>]";
    }

    @Override
    public String summary() {
        return "the IDE classpath: direct jars (compile) or the test closure (--test)";
    }

    @Override
    public int execute(List<String> args) {
        boolean test = false;
        Path file = null;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--test" -> test = true;
                case "--file" -> {
                    if (i + 1 >= args.size()) {
                        System.err.println("mjm: --file needs a path");
                        return 2;
                    }
                    file = projectDir.resolve(args.get(++i));
                }
                default -> {
                    System.err.println("mjm: unknown classpath option '" + args.get(i)
                            + "' — use --test or --file <path>");
                    return 2;
                }
            }
        }

        MjProject project;
        List<LockedArtifact> locked;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
            if (!Files.exists(projectDir.resolve("mjm.lock"))) {
                System.err.println("mjm: no mjm.lock in " + projectDir
                        + " — run mjm lock first");
                return 1;
            }
            locked = LockFileReader.read(Files.readString(projectDir.resolve("mjm.lock")));
        } catch (IOException e) {
            System.err.println("mjm: no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }

        List<Path> entries = new ArrayList<>();
        if (test) {
            entries.add(projectDir.resolve(TEST_CLASSES));
        }
        entries.add(projectDir.resolve(CLASSES));

        Map<String, LockedArtifact> byId = new LinkedHashMap<>();
        for (LockedArtifact artifact : locked) {
            byId.put(artifact.id(), artifact);
        }

        List<LockedArtifact> wanted = new ArrayList<>();
        if (test) {
            // the whole closure, main and dev alike; processors are build tools
            // and never belong on an IDE classpath
            for (LockedArtifact artifact : locked) {
                if (artifact.scope() != Scope.PROCESSOR) {
                    wanted.add(artifact);
                }
            }
        } else {
            // strict visibility: only what the manifest declares directly
            for (Requirement requirement : project.requirements()) {
                LockedArtifact artifact = byId.get(requirement.id());
                if (artifact == null) {
                    System.err.println("mjm: mjm.lock is stale — '" + requirement.id()
                            + "' is declared in mjm.toml but not locked; run mjm lock");
                    return 1;
                }
                wanted.add(artifact);
            }
        }

        for (LockedArtifact artifact : wanted) {
            Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
            if (jar == null) {
                System.err.println("mjm: " + artifact.id() + "@" + artifact.version()
                        + " is not in the store — run mjm lock");
                return 1;
            }
            entries.add(jar);
        }

        String line = entries.stream().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        if (file != null) {
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, line + System.lineSeparator());
            } catch (IOException e) {
                System.err.println("mjm: cannot write " + file + " — " + e.getMessage());
                return 1;
            }
            return 0;
        }
        System.out.println(line);
        return 0;
    }
}
