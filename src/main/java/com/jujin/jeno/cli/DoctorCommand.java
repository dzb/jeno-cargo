package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.Paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jeno doctor} — a local, read-only diagnosis of the project files.
 *
 * <p>It exists because the two file-format questions users hit first are not
 * build errors: "is this an old project file or the current
 * {@code jeno.toml}?" and "why does build say lock missing?" The doctor names
 * both files, validates the manifest and lock shapes, checks every module in a
 * workspace, and reports whether the XDG roots can be created. It never
 * resolves dependencies, writes files, or contacts a repository.
 */
public final class DoctorCommand implements Command {

    private final Path projectDir;

    public DoctorCommand(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "doctor";
    }

    @Override
    public String summary() {
        return "check manifest/lock names, workspace modules, and local jeno paths";
    }

    @Override
    public String help() {
        return """
               local only: it does not resolve, build, write, or contact a repository.
               at a workspace root it checks every module; elsewhere it checks the
               nearest project. Legacy project files are reported, never read.""";
    }

    @Override
    public Command.Category category() {
        return Command.Category.INSPECT;
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.NEVER)
                .workspace(CommandCapabilities.WorkspaceMode.GLOBAL)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        boolean json = args.contains("--json");
        for (String arg : args) {
            if (!arg.equals("--json")) {
                String message = "doctor takes no arguments — got '" + arg + "'";
                if (json) {
                    JsonResult.error("doctor", 2, "USAGE", message).emit(2);
                } else {
                    System.err.println(Ui.errPrefix() + message);
                }
                return 2;
            }
        }

        Report report = diagnose();
        if (json) {
            emitJson(report);
        } else {
            emitHuman(report);
        }
        return report.ok() ? 0 : 1;
    }

    private Report diagnose() {
        Path root = projectDir.toAbsolutePath().normalize();
        Workspaces.Workspace workspace = null;
        String workspaceError = null;
        try {
            workspace = Workspaces.rootAt(root).orElse(null);
        } catch (Workspaces.WorkspaceException e) {
            workspaceError = e.getMessage();
        }
        if (workspace == null && workspaceError == null
                && !Files.isRegularFile(root.resolve(Workspaces.FILE))
                && Files.isRegularFile(root.resolve(LegacyNames.WORKSPACE))) {
            workspaceError = LegacyNames.workspace(root);
        }

        List<ModuleReport> modules = new ArrayList<>();
        if (workspace != null) {
            for (Path module : workspace.modules()) {
                modules.add(checkModule(module, Workspaces.display(workspace.root(), module)));
            }
        } else if (workspaceError == null) {
            modules.add(checkModule(root, "."));
        } else {
            modules.add(new ModuleReport(".", root,
                    new ArrayList<>(List.of(CheckResult.failed("workspace", workspaceError))),
                    null));
        }

        if (!modules.isEmpty()) {
            List<CheckResult> hints = new ArrayList<>();
            Path legacyHome = LegacyNames.legacyHome();
            if (Files.isDirectory(legacyHome)) {
                hints.add(CheckResult.skipped("legacy-home",
                        LegacyNames.homeHint(legacyHome)));
            }
            List<String> legacyEnv = LegacyNames.environment();
            if (!legacyEnv.isEmpty()) {
                hints.add(CheckResult.skipped("legacy-env",
                        LegacyNames.environmentHint(legacyEnv)));
            }
            modules.getFirst().checks().addAll(hints);
        }

        List<PathReport> paths = List.of(
                checkWritable(Paths.store(), "store"),
                checkWritable(Paths.sources(), "sources"),
                checkWritable(Paths.locks(), "locks"));
        return new Report(root, workspace, workspaceError, modules, paths);
    }

    /** One module's files: names first, then schema and lock shape. */
    private static ModuleReport checkModule(Path dir, String label) {
        List<CheckResult> checks = new ArrayList<>();
        JenoProject project = null;

        Path legacyManifest = dir.resolve(LegacyNames.MANIFEST);
        Path legacyLock = dir.resolve(LegacyNames.LOCK);
        if (Files.isRegularFile(legacyManifest)) {
            checks.add(CheckResult.failed("legacy-manifest", LegacyNames.manifest(dir)));
        }
        if (Files.isRegularFile(legacyLock)) {
            checks.add(CheckResult.failed("legacy-lock", LegacyNames.lock(dir)));
        }

        Path manifest = ProjectFiles.manifest(dir);
        if (!Files.isRegularFile(manifest)) {
            checks.add(CheckResult.failed("manifest-missing",
                    "no jeno.toml — run jeno init"));
        } else {
            try {
                project = ProjectReader.parse(Files.readString(manifest));
                checks.add(CheckResult.ok("manifest", "jeno.toml (" + project.name() + "@"
                        + project.version() + ")"));
            } catch (IOException | RuntimeException e) {
                String message = e.getMessage();
                if (!message.contains("jeno.toml")) {
                    message = "jeno.toml: " + message;
                }
                checks.add(CheckResult.failed("manifest-invalid", message));
            }
        }

        Path lock = ProjectFiles.lock(dir);
        if (!Files.isRegularFile(lock)) {
            checks.add(CheckResult.failed("lock-missing", "no jeno.lock — run jeno lock"));
        } else {
            try {
                int count = LockFileReader.read(Files.readString(lock)).size();
                checks.add(CheckResult.ok("lock", "jeno.lock (" + count + " artifact(s))"));
            } catch (IOException | RuntimeException e) {
                checks.add(CheckResult.failed("lock-invalid", "jeno.lock: " + e.getMessage()));
            }
        }
        checks.add(CheckResult.skipped("declarations",
                "not compared with jeno.lock — run jeno lock --check"));
        return new ModuleReport(label, dir, checks, project);
    }

    /** The nearest existing ancestor must be a writable directory. */
    private static PathReport checkWritable(Path target, String label) {
        Path existing = target.toAbsolutePath().normalize();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null || !Files.isDirectory(existing) || !Files.isWritable(existing)) {
            return new PathReport(label, target,
                    CheckResult.failed(label, label + " path is not writable: " + target));
        }
        return new PathReport(label, target, CheckResult.ok(label, label + " " + target));
    }

    private static void emitHuman(Report report) {
        if (report.workspace() != null) {
            System.out.println(Ui.heading("doctor: workspace ") + report.workspace().root());
            for (ModuleReport module : report.modules()) {
                printModule(module);
            }
        } else if (report.workspaceError() == null) {
            System.out.println(Ui.heading("doctor: project ") + report.root());
            for (ModuleReport module : report.modules()) {
                printModule(module);
            }
        } else {
            problem(report.workspaceError());
        }

        System.out.println(Ui.heading("local paths:"));
        for (PathReport path : report.paths()) {
            printCheck(path.check());
        }
        System.out.println(report.ok() ? Ui.ok("doctor: ok") : Ui.fail("doctor: problems found"));
    }

    private static void printModule(ModuleReport module) {
        System.out.println(Ui.heading("module: ") + module.label());
        for (CheckResult check : module.checks()) {
            printCheck(check);
        }
    }

    private static void printCheck(CheckResult check) {
        switch (check.status()) {
            case OK -> System.out.println("  " + Ui.check(check.message()));
            case FAILED -> System.out.println("  " + Ui.cross(check.message()));
            case SKIPPED -> System.out.println("  " + Ui.dim("- " + check.message()));
        }
    }

    private static void emitJson(Report report) {
        JsonResult result = JsonResult.start("doctor");
        String mode = report.workspace() != null ? "all"
                : report.workspaceError() == null ? "single" : "unknown";
        result.workspace(report.root(), mode);

        JsonArray modules = JsonUtils.array();
        int allChecks = 0;
        int passedChecks = 0;
        int failedChecks = 0;
        int skippedChecks = 0;
        for (ModuleReport module : report.modules()) {
            JsonObject entry = JsonUtils.object();
            entry.put("module", module.label());
            entry.put("path", module.dir().toAbsolutePath().normalize().toString());
            entry.put("status", module.ok() ? "ok" : "failed");
            entry.put("exitCode", module.ok() ? 0 : 1);
            if (module.project() != null) {
                JsonObject identity = JsonUtils.object();
                identity.put("name", module.project().name());
                identity.put("version", module.project().version());
                entry.put("project", identity);
            }
            JsonArray checks = JsonUtils.array();
            for (CheckResult check : module.checks()) {
                checks.add(check.json());
                allChecks++;
                switch (check.status()) {
                    case OK -> passedChecks++;
                    case FAILED -> {
                        failedChecks++;
                        result.diagnostic("error", check.id(), check.message(), List.of());
                    }
                    case SKIPPED -> skippedChecks++;
                }
            }
            entry.put("checks", checks);
            modules.add(entry);
        }
        result.put("modules", modules);

        JsonArray paths = JsonUtils.array();
        int failedPaths = 0;
        for (PathReport path : report.paths()) {
            JsonObject entry = JsonUtils.object();
            entry.put("id", path.label());
            entry.put("target", path.target().toAbsolutePath().normalize().toString());
            entry.put("status", path.check().status().name().toLowerCase(java.util.Locale.ROOT));
            entry.put("message", path.check().message());
            paths.add(entry);
            if (path.check().failed()) {
                failedPaths++;
                result.diagnostic("error", path.check().id(), path.check().message(), List.of());
            }
        }
        result.put("localPaths", paths);

        JsonObject summary = JsonUtils.object();
        long failedModules = report.modules().stream().filter(module -> !module.ok()).count();
        summary.put("modules", report.modules().size());
        summary.put("failedModules", failedModules);
        summary.put("localPaths", report.paths().size());
        summary.put("failedLocalPaths", failedPaths);
        summary.put("checks", allChecks);
        summary.put("passedChecks", passedChecks);
        summary.put("failedChecks", failedChecks);
        summary.put("skippedChecks", skippedChecks);
        result.put("summary", summary);
        if (report.workspaceError() != null) {
            result.diagnostic("error", "WORKSPACE_INVALID", report.workspaceError(), List.of());
        }
        result.emit(report.ok() ? 0 : 1);
    }

    private static void problem(String message) {
        System.out.println("  " + Ui.cross(message));
    }

    private record ModuleReport(String label, Path dir, List<CheckResult> checks, JenoProject project) {
        boolean ok() {
            return checks.stream().noneMatch(CheckResult::failed);
        }
    }

    private record PathReport(String label, Path target, CheckResult check) {}

    private record Report(Path root, Workspaces.Workspace workspace, String workspaceError,
                          List<ModuleReport> modules, List<PathReport> paths) {
        boolean ok() {
            return workspaceError == null
                    && modules.stream().allMatch(ModuleReport::ok)
                    && paths.stream().noneMatch(path -> path.check().failed());
        }
    }
}
