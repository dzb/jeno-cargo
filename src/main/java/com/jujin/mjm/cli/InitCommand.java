package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.pom.PomMigration;
import com.jujin.mjm.pom.PomSource;
import com.jujin.mjm.project.ProjectReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code mjm init} — start a project, or migrate one from Maven.
 *
 * <pre>
 *   mjm init                    skeleton mjm.toml for the current directory
 *   mjm init --pom         translate ./pom.xml into mjm.toml (reviewed, not
 *                              imported: every unrepresentable fact is printed)
 *   mjm init --ws [paths]   write mjm.ws.toml — the local workspace file
 * </pre>
 *
 * <p>The migrated file is validated through {@link ProjectReader} before it is
 * written — a translation that mjm itself would refuse never lands on disk.
 * An existing mjm.toml is never overwritten without {@code --force}.
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
        return "create mjm.toml (a skeleton, --pom to migrate, --ws for a workspace file)";
    }
    @Override
    public String usage() {
        return "[--pom] [--ws] [--force] [paths...]";
    }


    @Override
    public int execute(List<String> args) {
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
                        System.err.println("mjm: unknown init option '" + arg + "'"
                                + " — use --pom, --ws or --force");
                        return 2;
                    }
                    paths.add(arg);
                }
            }
        }
        if (workspace) {
            if (migratePom) {
                System.err.println("mjm: --ws and --pom cannot be combined");
                return 2;
            }
            return initWorkspace(paths, force);
        }
        if (!paths.isEmpty()) {
            System.err.println("mjm: init takes no paths without --ws — got '" + paths.getFirst()
                    + "'");
            return 2;
        }

        // A workspace root is a composition of projects, not a project: writing
        // mjm.toml here would make it a module and break the workspace (and the
        // reader refuses that combination anyway). Plain init, --pom and --force
        // are all refused; init --ws is the one command that belongs here.
        if (Files.isRegularFile(projectDir.resolve(Workspaces.FILE))) {
            System.err.println("mjm: " + projectDir + " is a workspace root (it has "
                    + Workspaces.FILE + ") — run init from a parent directory, or cd into a "
                    + "module; a workspace root does not hold mjm.toml");
            return 1;
        }

        Path target = projectDir.resolve("mjm.toml");
        if (Files.exists(target) && !force) {
            System.err.println("mjm: " + target + " already exists — pass --force to overwrite");
            return 1;
        }

        String toml;
        if (migratePom) {
            Path pom = projectDir.resolve("pom.xml");
            if (!Files.exists(pom)) {
                System.err.println("mjm: no pom.xml in " + projectDir + " to migrate");
                return 1;
            }
            PomMigration.Result result;
            try {
                result = PomMigration.translate(pom, pomSource);
            } catch (RuntimeException e) {
                System.err.println("mjm: " + e.getMessage());
                return 1;
            }
            for (String note : result.notes()) {
                System.out.println("  note: " + note);
            }
            if (result.multiModuleRoot()) {
                System.err.println("mjm: " + projectDir + " is a multi-module pom root"
                        + " (packaging pom, modules: " + String.join(", ", result.modules())
                        + ") — mjm does not recurse; run mjm init --ws here, then"
                        + " mjm init --pom inside each module (no mjm.toml was written)");
                return 1;
            }
            toml = result.mjToml();
        } else {
            toml = skeleton(nameOf(projectDir));
        }

        // never write a file mjm would refuse to read
        try {
            ProjectReader.parse(toml);
        } catch (RuntimeException e) {
            System.err.println("mjm: the generated mjm.toml would not parse — " + e.getMessage());
            return 1;
        }

        try {
            Files.writeString(target, toml);
        } catch (IOException e) {
            System.err.println("mjm: cannot write " + target);
            return 1;
        }

        System.out.println("created " + target
                + (migratePom ? " (migrated from pom.xml)" : ""));
        System.out.println("next: mjm add \"group:artifact\" <version>   then   mjm lock");
        return 0;
    }

    /**
     * {@code mjm init --ws}: write {@code mjm.ws.toml}, either from explicit
     * module paths or from a scan of the current directory three levels deep.
     */
    private int initWorkspace(List<String> given, boolean force) {
        Path file = projectDir.resolve(Workspaces.FILE);
        if (Files.exists(file) && !force) {
            System.err.println("mjm: " + file + " already exists — pass --force to overwrite");
            return 1;
        }
        if (Files.isRegularFile(projectDir.resolve("mjm.toml"))) {
            System.err.println("mjm: " + projectDir + " is a module (it has mjm.toml) and cannot "
                    + "also be the workspace root — run mjm init --ws from a parent directory");
            return 1;
        }

        List<Path> modules;
        if (given.isEmpty()) {
            modules = scan(projectDir);
            if (modules.isEmpty()) {
                System.err.println("mjm: no modules found under " + projectDir
                        + " (looked 3 levels deep) — pass the paths explicitly: "
                        + "mjm init --ws libs/core apps/web");
                return 1;
            }
        } else {
            List<Path> seen = new ArrayList<>();
            for (String text : given) {
                Path module = projectDir.resolve(text).normalize();
                if (!Files.isDirectory(module)) {
                    System.err.println("mjm: '" + text + "' is not a directory");
                    return 1;
                }
                if (!Files.isRegularFile(module.resolve("mjm.toml"))) {
                    System.err.println("mjm: '" + text + "' has no mjm.toml — a workspace module "
                            + "needs one");
                    return 1;
                }
                seen.add(module);
            }
            modules = dedupe(seen);
        }

        StringBuilder out = new StringBuilder();
        out.append("# generated by mjm init --ws — a local file, not meant to be committed\n");
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

        // never write a workspace mjm would refuse to read
        try {
            Files.writeString(file, out.toString());
            Workspaces.read(projectDir).orElseThrow(() ->
                    new Workspaces.WorkspaceException("the generated file could not be read back"));
        } catch (IOException e) {
            System.err.println("mjm: cannot write " + file + " — " + e.getMessage());
            return 1;
        } catch (Workspaces.WorkspaceException e) {
            System.err.println("mjm: the generated workspace would not parse — " + e.getMessage());
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // the file was unusable either way
            }
            return 1;
        }

        System.out.println("created " + file);
        System.out.println("modules (" + modules.size() + "):");
        for (Path module : modules) {
            System.out.println("  " + projectDir.relativize(module).toString().replace('\\', '/'));
        }
        return 0;
    }

    /** Directories under {@code root} up to three levels down that hold mjm.toml. */
    static List<Path> scan(Path root) {
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
                    .filter(path -> Files.isRegularFile(path.resolve("mjm.toml")))
                    .forEach(found::add);
        } catch (IOException e) {
            return List.of();
        }
        return dedupe(found);
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

    /** The skeleton: a directory name is a valid mjm project name, or gets one. */
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
