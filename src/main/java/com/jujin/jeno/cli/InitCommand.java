package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.jeno.pom.PomMigration;
import com.jujin.jeno.pom.PomSource;
import com.jujin.jeno.project.ProjectReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jeno init} — start a project, or migrate one from Maven.
 *
 * <pre>
 *   jeno init                    skeleton jeno.toml for the current directory
 *   jeno init --pom         translate ./pom.xml into jeno.toml (reviewed, not
 *                              imported: every unrepresentable fact is printed)
 *   jeno init --ws [paths]   write jeno.ws.toml — the local workspace file
 * </pre>
 *
 * <p>The migrated file is validated through {@link ProjectReader} before it is
 * written — a translation that jeno itself would refuse never lands on disk.
 * An existing jeno.toml is never overwritten without {@code --force}.
 */
public final class InitCommand implements Command {

    private final Path projectDir;
    private final PomSource pomSource;

    /** Local-only init (tests and callers that have no store). */
    public InitCommand(Path projectDir) {
        this(projectDir, null);
    }

    @Inject
    public InitCommand(Path projectDir, PomSource pomSource) {
        this.projectDir = projectDir;
        this.pomSource = pomSource;
    }

    @Override
    public String name() {
        return "init";
    }

    @Override
    public String summary() {
        return "create jeno.toml (a skeleton, --pom to migrate, --ws for a workspace file)";
    }
    @Override
    public String usage() {
        return "[--pom] [--ws] [--force] [paths...]";
    }


    @Override
    public Command.Category category() {
        return Command.Category.PROJECT;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.OPTIONAL)
                .writes(CommandCapabilities.WriteScope.PROJECT,
                        CommandCapabilities.WriteScope.CACHE)
                .workspace(CommandCapabilities.WorkspaceMode.GLOBAL)
                .destructive()
                .build();
    }

    @Override
    public int execute(List<String> args) {
        boolean json = args.contains("--json");
        List<String> cleaned = args.stream().filter(arg -> !arg.equals("--json")).toList();
        if (!json) {
            return executeHuman(cleaned);
        }

        boolean workspace = cleaned.contains("--ws");
        Path target = projectDir.resolve(workspace ? Workspaces.FILE : "jeno.toml");
        boolean existedBefore = Files.exists(target);
        JsonCapture.Result captured = JsonCapture.run(() -> executeHuman(cleaned));
        if (captured.exitCode() != 0) {
            String code = captured.exitCode() == 2 ? "USAGE" : "INIT_FAILED";
            JsonResult.error("init", captured.exitCode(), code, captured.message())
                    .emit(captured.exitCode());
            return captured.exitCode();
        }

        if (workspace) {
            try {
                Workspaces.Workspace parsed = Workspaces.read(projectDir).orElseThrow(() ->
                        new Workspaces.WorkspaceException("the generated workspace was not readable"));
                JsonArray modules = JsonUtils.array();
                for (Path module : parsed.modules()) {
                    modules.add(parsed.root().relativize(module).toString().replace('\\', '/'));
                }
                JsonResult.start("init")
                        .workspace(projectDir, "global")
                        .put("mode", "workspace")
                        .put("file", target.toAbsolutePath().normalize().toString())
                        .put("overwritten", existedBefore)
                        .put("moduleCount", modules.size())
                        .put("modules", modules)
                        .emit(0);
                return 0;
            } catch (RuntimeException e) {
                JsonResult.error("init", 1, "RESULT_UNREADABLE", e.getMessage()).emit(1);
                return 1;
            }
        }

        try {
            var project = ProjectReader.parse(Files.readString(target));
            JsonObject identity = JsonUtils.object();
            identity.put("name", project.name());
            identity.put("version", project.version());
            JsonResult.start("init")
                    .workspace(projectDir, "global")
                    .put("mode", cleaned.contains("--pom") ? "pom" : "project")
                    .put("file", target.toAbsolutePath().normalize().toString())
                    .put("overwritten", existedBefore)
                    .put("project", identity)
                    .put("nextActions", JsonUtils.array()
                            .add(JsonUtils.array().add("jeno").add("lock")))
                    .emit(0);
            return 0;
        } catch (IOException | RuntimeException e) {
            JsonResult.error("init", 1, "RESULT_UNREADABLE", e.getMessage()).emit(1);
            return 1;
        }
    }

    private int executeHuman(List<String> args) {
        boolean migratePom = false;
        boolean workspace = false;
        boolean force = false;
        List<String> paths = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "--pom" -> migratePom = true;
                case "--ws" -> workspace = true;
                case "--force" -> force = true;
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println(Ui.errPrefix() + "unknown init option '" + arg + "'"
                                + " — use --pom, --ws or --force");
                        return 2;
                    }
                    paths.add(arg);
                }
            }
        }
        if (workspace) {
            if (migratePom) {
                System.err.println(Ui.errPrefix() + "--ws and --pom cannot be combined");
                return 2;
            }
            return initWorkspace(paths, force);
        }
        if (!paths.isEmpty()) {
            System.err.println(Ui.errPrefix() + "init takes no paths without --ws — got '" + paths.getFirst()
                    + "'");
            return 2;
        }

        // A workspace root is a composition of projects, not a project: writing
        // jeno.toml here would make it a module and break the workspace (and the
        // reader refuses that combination anyway). Plain init, --pom and --force
        // are all refused; init --ws is the one command that belongs here.
        if (Files.isRegularFile(projectDir.resolve(Workspaces.FILE))) {
            System.err.println(Ui.errPrefix() + projectDir + " is a workspace root (it has "
                    + Workspaces.FILE + ") — run init from a parent directory, or cd into a "
                    + "module; a workspace root does not hold jeno.toml");
            return 1;
        }

        Path target = projectDir.resolve("jeno.toml");
        if (Files.exists(target) && !force) {
            System.err.println(Ui.errPrefix() + target + " already exists — pass --force to overwrite");
            return 1;
        }

        String toml;
        if (migratePom) {
            Path pom = projectDir.resolve("pom.xml");
            if (!Files.exists(pom)) {
                System.err.println(Ui.errPrefix() + "no pom.xml in " + projectDir + " to migrate");
                return 1;
            }
            PomMigration.Result result;
            try {
                result = PomMigration.translate(pom, pomSource);
            } catch (RuntimeException e) {
                System.err.println(Ui.errPrefix() + e.getMessage());
                return 1;
            }
            for (String note : result.notes()) {
                System.out.println("  " + Ui.warn("note: " + note));
            }
            if (result.multiModuleRoot()) {
                System.err.println(Ui.errPrefix() + projectDir + " is a multi-module pom root"
                        + " (packaging pom, modules: " + String.join(", ", result.modules())
                        + ") — jeno does not recurse; run jeno init --ws here, then"
                        + " jeno init --pom inside each module (no jeno.toml was written)");
                return 1;
            }
            toml = result.mjToml();
        } else {
            toml = skeleton(nameOf(projectDir));
        }

        // never write a file jeno would refuse to read
        try {
            ProjectReader.parse(toml);
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + "the generated jeno.toml would not parse — " + e.getMessage());
            return 1;
        }

        try {
            Files.writeString(target, toml);
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot write " + target);
            return 1;
        }

        System.out.println(Ui.ok("created " + target
                + (migratePom ? " (migrated from pom.xml)" : "")));
        System.out.println(Ui.dim("next: jeno add \"group:artifact\" <version>   then   jeno lock"));
        return 0;
    }

    /**
     * {@code jeno init --ws}: write {@code jeno.ws.toml}, either from explicit
     * module paths or from a scan of the current directory three levels deep.
     */
    private int initWorkspace(List<String> given, boolean force) {
        Path file = projectDir.resolve(Workspaces.FILE);
        if (Files.exists(file) && !force) {
            System.err.println(Ui.errPrefix() + file + " already exists — pass --force to overwrite");
            return 1;
        }
        if (Files.isRegularFile(projectDir.resolve("jeno.toml"))) {
            System.err.println(Ui.errPrefix() + projectDir + " is a module (it has jeno.toml) and cannot "
                    + "also be the workspace root — run jeno init --ws from a parent directory");
            return 1;
        }

        String legacyName = LegacyNames.MANIFEST;
        List<Path> modules;
        if (given.isEmpty()) {
            modules = scan(projectDir);
            List<Path> legacy = scanLegacy(projectDir);
            if (modules.isEmpty()) {
                System.err.println(Ui.errPrefix() + "no modules found under " + projectDir
                        + " (looked 3 levels deep)");
                if (!legacy.isEmpty()) {
                    System.err.println(Ui.errPrefix() + "found legacy " + legacyName + " in: "
                            + relativeList(projectDir, legacy));
                    System.err.println("  jeno does not read " + legacyName + " — create or migrate jeno.toml "
                            + "in each module (one way: jeno init --pom), then rerun jeno init --ws");
                } else {
                    System.err.println("  pass the paths explicitly: "
                            + "jeno init --ws libs/core apps/web");
                }
                return 1;
            }
            if (!legacy.isEmpty()) {
                System.err.println(Ui.errPrefix() + "warning: ignored legacy " + legacyName + " in: "
                        + relativeList(projectDir, legacy));
                System.err.println("  a workspace module is discovered by jeno.toml, never "
                        + legacyName);
            }
        } else {
            List<Path> seen = new ArrayList<>();
            for (String text : given) {
                Path module = projectDir.resolve(text).normalize();
                if (!Files.isDirectory(module)) {
                    System.err.println(Ui.errPrefix() + "'" + text + "' is not a directory");
                    return 1;
                }
                if (!Files.isRegularFile(module.resolve("jeno.toml"))) {
                    if (Files.isRegularFile(module.resolve(legacyName))) {
                        System.err.println(Ui.errPrefix() + "'" + text + "' has " + legacyName + ", not jeno.toml — "
                                + "jeno does not read the old name; migrate it (jeno init --pom) "
                                + "or rename the file, then run jeno lock");
                    } else {
                        System.err.println(Ui.errPrefix() + "'" + text + "' has no jeno.toml — a workspace "
                                + "module needs one");
                    }
                    return 1;
                }
                seen.add(module);
            }
            modules = dedupe(seen);
        }

        StringBuilder out = new StringBuilder();
        out.append("# generated by jeno init --ws — a local file, not meant to be committed\n");
        out.append("[ws]\n");
        out.append("modules = [");
        for (int i = 0; i < modules.size(); i++) {
            String relative = projectDir.relativize(modules.get(i)).toString()
                    .replace('\\', '/');
            out.append(i == 0 ? "" : ", ")
                    .append('"').append(relative.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        out.append("]\n");

        // never write a workspace jeno would refuse to read
        try {
            Files.writeString(file, out.toString());
            Workspaces.read(projectDir).orElseThrow(() ->
                    new Workspaces.WorkspaceException("the generated file could not be read back"));
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot write " + file + " — " + e.getMessage());
            return 1;
        } catch (Workspaces.WorkspaceException e) {
            System.err.println(Ui.errPrefix() + "the generated workspace would not parse — " + e.getMessage());
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // the file was unusable either way
            }
            return 1;
        }

        System.out.println(Ui.ok("created " + file));
        System.out.println(Ui.heading("modules (" + modules.size() + "):"));
        for (Path module : modules) {
            System.out.println("  " + projectDir.relativize(module).toString().replace('\\', '/'));
        }
        return 0;
    }

    /** Directories under {@code root} up to three levels down that hold jeno.toml. */
    static List<Path> scan(Path root) {
        return scanNamed(root, "jeno.toml");
    }

    /** Directories that still hold the old project manifest and have no jeno.toml. */
    static List<Path> scanLegacy(Path root) {
        return scanNamed(root, LegacyNames.MANIFEST).stream()
                .filter(path -> !Files.isRegularFile(path.resolve("jeno.toml")))
                .toList();
    }

    private static List<Path> scanNamed(Path root, String manifestName) {
        java.util.Set<String> skipped = java.util.Set.of(
                "target", "dist", "node_modules", ".git");
        List<Path> found = new ArrayList<>();
        try (var stream = Files.walk(root, 3)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .filter(path -> {
                        for (Path part : root.relativize(path)) {
                            String name = part.toString();
                            if (skipped.contains(name) || name.startsWith(".")) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .filter(path -> Files.isRegularFile(path.resolve(manifestName)))
                    .forEach(found::add);
        } catch (IOException e) {
            return List.of();
        }
        return dedupe(found);
    }

    private static String relativeList(Path root, List<Path> modules) {
        return modules.stream()
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * One entry per real directory: a symlinked route and the directory it
     * points at are the same module, and {@link Files#walk} without
     * {@code FOLLOW_LINKS} already refuses to descend through links.
     */
    private static List<Path> dedupe(List<Path> paths) {
        java.util.Map<String, Path> unique = new java.util.LinkedHashMap<>();
        for (Path path : paths) {
            try {
                unique.putIfAbsent(path.toRealPath().toString(), path);
            } catch (IOException e) {
                unique.putIfAbsent(path.toAbsolutePath().normalize().toString(), path);
            }
        }
        List<Path> result = new ArrayList<>(unique.values());
        result.sort(java.util.Comparator.comparing(path ->
                path.toString().replace('\\', '/')));
        return result;
    }

    /** The skeleton: a directory name is a valid jeno project name, or gets one. */
    private static String skeleton(String name) {
        return """
            [project]
            name = "%s"
            version = "0.1.0"

            [java]
            version = 25

            [deps]
            """.formatted(name);
    }

    /** A lowercase, dash-separated project name derived from the directory. */
    static String nameOf(Path dir) {
        Path fileName = dir.getFileName();
        String raw = fileName == null ? "app" : fileName.toString();
        String candidate = raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (candidate.isEmpty() || !candidate.matches("[a-z].*")) {
            return "app";
        }
        return candidate;
    }
}
