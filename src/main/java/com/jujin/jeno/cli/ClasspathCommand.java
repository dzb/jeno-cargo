package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.project.Requirement;
import com.jujin.jeno.resolve.Scope;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;

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
    public Command.Category category() {
        return Command.Category.INSPECT;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.NEVER)
                .writes(CommandCapabilities.WriteScope.PROJECT)
                .workspace(CommandCapabilities.WorkspaceMode.MODULE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        boolean json = args.contains("--json");
        if (json && Workspaces.rootAt(projectDir).isPresent() && !hasModuleOption(args)) {
            JsonResult.error("classpath", 2, "MODULE_REQUIRED",
                    "this directory is a workspace root — say which module: --module <path>")
                    .emit(2);
            return 2;
        }
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new ClasspathCommand(store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean test = false;
        Path file = null;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--json" -> { }
                case "--test" -> test = true;
                case "--file" -> {
                    if (i + 1 >= args.size()) {
                        String message = "--file needs a path";
                        if (json) {
                            JsonResult.error("classpath", 2, "USAGE", message).emit(2);
                        } else {
                            System.err.println(Ui.errPrefix() + message);
                        }
                        return 2;
                    }
                    file = projectDir.resolve(args.get(++i));
                }
                default -> {
                    String message = "unknown classpath option '" + args.get(i)
                            + "' — use --test or --file <path>";
                    if (json) {
                        JsonResult.error("classpath", 2, "USAGE", message).emit(2);
                    } else {
                        System.err.println(Ui.errPrefix() + message);
                    }
                    return 2;
                }
            }
        }

        JenoProject project;
        List<LockedArtifact> locked;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
            if (!Files.exists(projectDir.resolve("mjm.lock"))) {
                String message = "no mjm.lock in " + projectDir + " — run mjm lock first";
                if (json) {
                    JsonResult.error("classpath", 1, "LOCK_MISSING", message).emit(1);
                } else {
                    System.err.println(Ui.errPrefix() + message);
                }
                return 1;
            }
            locked = LockFileReader.read(Files.readString(projectDir.resolve("mjm.lock")));
        } catch (IOException e) {
            String message = "no mjm.toml in " + projectDir + " — run mjm init to create one";
            if (json) {
                JsonResult.error("classpath", 1, "MANIFEST_MISSING", message).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + message);
            }
            return 1;
        } catch (RuntimeException e) {
            if (json) {
                JsonResult.error("classpath", 1, "MANIFEST_OR_LOCK_INVALID",
                        e.getMessage()).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + e.getMessage());
            }
            return 1;
        }

        ClasspathAssembler.Classpath classpath;
        try {
            classpath = ClasspathAssembler.of(
                    test ? ClasspathAssembler.Mode.EXPORT_TEST
                            : ClasspathAssembler.Mode.EXPORT_MAIN,
                    projectDir, store, ClasspathAssembler.Input.export(project, locked));
        } catch (ClasspathAssembler.ClasspathException e) {
            if (json) {
                JsonResult.error("classpath", 1, "CLASSPATH_FAILED",
                        e.getMessage()).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + e.getMessage());
            }
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
                String message = "cannot write " + file + " — " + e.getMessage();
                if (json) {
                    JsonResult.error("classpath", 1, "WRITE_FAILED", message).emit(1);
                } else {
                    System.err.println(Ui.errPrefix() + message);
                }
                return 1;
            }
            if (json) {
                emitJson(test, classpath, file);
            }
            return 0;
        }
        if (json) {
            emitJson(test, classpath, null);
        } else {
            System.out.println(line);
        }
        return 0;
    }

    private static void emitJson(boolean test, ClasspathAssembler.Classpath classpath,
                                 Path file) {
        JsonArray entries = JsonUtils.array();
        classpath.strings().forEach(entries::add);
        JsonResult result = JsonResult.start("classpath")
                .put("mode", test ? "test" : "compile")
                .put("classpath", classpath.joined())
                .put("entries", entries);
        if (file != null) {
            result.put("file", file.toAbsolutePath().normalize().toString())
                    .put("written", true);
        }
        result.emit(0);
    }

    private static boolean hasModuleOption(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("--module") || arg.startsWith("--module="));
    }
}
