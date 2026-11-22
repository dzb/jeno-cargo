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
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new ClasspathCommand(store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
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

        ClasspathAssembler.Classpath classpath;
        try {
            classpath = ClasspathAssembler.of(
                    test ? ClasspathAssembler.Mode.EXPORT_TEST
                            : ClasspathAssembler.Mode.EXPORT_MAIN,
                    projectDir, store, ClasspathAssembler.Input.export(project, locked));
        } catch (ClasspathAssembler.ClasspathException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }

        String line = classpath.joined();
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
