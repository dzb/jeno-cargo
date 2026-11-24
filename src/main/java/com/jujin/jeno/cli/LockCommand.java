package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jeno lock}: the shell around {@link LockService} — resolve, download,
 * pin (SPEC.md §4).
 */
public final class LockCommand implements Command {

    private final LockService lockService;
    private final Path projectDir;

    @Inject
    public LockCommand(LockService lockService, Path projectDir) {
        this.lockService = lockService;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "lock";
    }

    @Override
    public String summary() {
        return "resolve dependencies, download into the store, and pin jeno.lock";
    }

    @Override
    public String usage() {
        return "[--check]";
    }

    @Override
    public Command.Category category() {
        return Command.Category.PROJECT;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.OPTIONAL)
                .writes(CommandCapabilities.WriteScope.PROJECT,
                        CommandCapabilities.WriteScope.CACHE,
                        CommandCapabilities.WriteScope.LOCKS)
                .workspace(CommandCapabilities.WorkspaceMode.AGGREGATE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        ProjectFiles.rejectLegacy(projectDir);
        boolean json = args.contains("--json");
        List<String> cleaned = new ArrayList<>();
        for (String arg : args) {
            if (!arg.equals("--json")) {
                cleaned.add(arg);
            }
        }
        if (json) {
            return lockJson(cleaned);
        }

        Integer workspace = WorkspaceRun.orchestrate(projectDir, cleaned, "locked", false,
                (dir, rest) -> new LockCommand(lockService, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean check = false;
        for (String arg : cleaned) {
            if (arg.equals("--check")) {
                check = true;
            } else if (arg.equals("--refresh")) {
                System.err.println(Ui.errPrefix() + "--refresh was removed — jeno lock always re-resolves; "
                        + "use --check to only compare");
                return 2;
            } else {
                System.err.println(Ui.errPrefix() + "unknown lock option '" + arg + "' — use --check");
                return 2;
            }
        }
        // lock writes both files (or, with --check, reads the state it would
        // write), so it queues with add/remove/build/test/clean
        if (check) {
            return ProjectLock.with(projectDir, this::checkHuman);
        }
        long started = System.nanoTime();
        return ProjectLock.with(projectDir, () -> {
            try {
                var graph = lockService.refresh(projectDir);
                System.out.println(Ui.ok("locked " + graph.artifacts().size()
                        + " artifact(s) into jeno.lock") + Ui.duration(started));
                return 0;
            } catch (RuntimeException e) {
                System.err.println(Ui.errPrefix() + e.getMessage());
                return 1;
            }
        });
    }

    /** Human rendering for the byte-for-byte lock check. */
    private int checkHuman() {
        LockModule result = inspectCheck(projectDir);
        if (result.fatalCode() != null) {
            System.err.println(Ui.errPrefix() + result.fatalMessage());
            return 1;
        }
        if (result.upToDate()) {
            System.out.println(Ui.ok("jeno.lock is up to date ("
                    + result.artifacts() + " artifacts)"));
            return 0;
        }

        System.out.println(Ui.warn("jeno.lock is out of date:"));
        for (String line : result.differences()) {
            System.out.println("  " + Ui.dim(line));
        }
        System.out.println("run jeno lock");
        return 1;
    }

    /** One document for single projects and the whole workspace. */
    private int lockJson(List<String> args) {
        boolean check = args.contains("--check");
        List<String> selectorArgs = new ArrayList<>();
        for (String arg : args) {
            if (!arg.equals("--check")) {
                selectorArgs.add(arg);
            }
        }

        WorkspaceRun.Run run;
        try {
            run = WorkspaceRun.root(projectDir, selectorArgs);
        } catch (Workspaces.UsageException e) {
            JsonResult.error("lock", 2, "USAGE", e.getMessage()).emit(2);
            return 2;
        } catch (Workspaces.WorkspaceException e) {
            JsonResult.error("lock", 1, "WORKSPACE_INVALID", e.getMessage()).emit(1);
            return 1;
        }

        Path root = projectDir.toAbsolutePath().normalize();
        String mode;
        List<Path> modules;
        if (run == null) {
            if (hasModuleOption(selectorArgs)) {
                JsonResult.error("lock", 2, "MODULE_WITHOUT_WORKSPACE",
                        "--module is only used at a workspace root").emit(2);
                return 2;
            }
            if (!selectorArgs.isEmpty()) {
                JsonResult.error("lock", 2, "USAGE",
                        "unknown lock option '" + selectorArgs.getFirst() + "' — use --check")
                        .emit(2);
                return 2;
            }
            modules = List.of(root);
            mode = "single";
        } else {
            if (!run.args().isEmpty()) {
                JsonResult.error("lock", 2, "USAGE",
                        "unknown lock option '" + run.args().getFirst() + "' — use --check")
                        .emit(2);
                return 2;
            }
            try {
                modules = run.module() != null
                        ? List.of(run.module()) : Workspaces.topological(run.workspace());
            } catch (Workspaces.WorkspaceException e) {
                JsonResult.error("lock", 1, "WORKSPACE_INVALID", e.getMessage()).emit(1);
                return 1;
            }
            root = run.workspace().root();
            mode = run.module() != null ? "module" : "all";
        }

        JsonResult result = JsonResult.start("lock")
                .workspace(root, mode)
                .put("check", check);
        JsonArray moduleArray = JsonUtils.array();
        int failedModules = 0;
        int upToDateModules = 0;
        int totalArtifacts = 0;
        for (Path moduleDir : modules) {
            LockModule module = lockedModule(moduleDir, check);
            String label = mode.equals("single") ? "." : Workspaces.display(root, moduleDir);
            JsonObject entry = JsonUtils.object();
            entry.put("module", label);
            entry.put("path", moduleDir.toAbsolutePath().normalize().toString());
            entry.put("lockFile", moduleDir.resolve("jeno.lock")
                    .toAbsolutePath().normalize().toString());
            entry.put("status", module.ok() ? "ok" : "failed");
            entry.put("exitCode", module.ok() ? 0 : 1);
            entry.put("check", check);
            entry.put("artifacts", module.artifacts());
            if (check) {
                entry.put("upToDate", module.upToDate());
                JsonArray differences = JsonUtils.array();
                module.differences().forEach(differences::add);
                entry.put("differences", differences);
            }
            if (module.fatalMessage() != null) {
                entry.put("message", module.fatalMessage());
                result.diagnostic("error", module.fatalCode(), module.fatalMessage(), List.of());
            } else if (check && !module.upToDate()) {
                result.diagnostic("error", "LOCK_STALE",
                        "jeno.lock is out of date", List.of(List.of("jeno", "lock")));
            }
            moduleArray.add(entry);
            totalArtifacts += module.artifacts();
            if (check && module.upToDate()) {
                upToDateModules++;
            }
            if (!module.ok()) {
                failedModules++;
            }
        }
        result.put("modules", moduleArray);

        JsonObject summary = JsonUtils.object();
        summary.put("modules", modules.size());
        summary.put("passedModules", modules.size() - failedModules);
        summary.put("failedModules", failedModules);
        summary.put("artifacts", totalArtifacts);
        if (check) {
            summary.put("upToDateModules", upToDateModules);
        }
        result.put("summary", summary);

        int exit = failedModules == 0 ? 0 : 1;
        result.emit(exit);
        return exit;
    }

    private LockModule lockedModule(Path moduleDir, boolean check) {
        LockModule[] holder = new LockModule[1];
        try {
            ProjectLock.with(moduleDir, () -> {
                holder[0] = check ? inspectCheck(moduleDir) : refreshModule(moduleDir);
                return holder[0].ok() ? 0 : 1;
            });
        } catch (RuntimeException e) {
            return LockModule.fatal(moduleDir, check,
                    "PROJECT_LOCK_FAILED", e.getMessage());
        }
        return holder[0];
    }

    private LockModule inspectCheck(Path dir) {
        String manifest;
        String onDisk;
        try {
            manifest = java.nio.file.Files.readString(dir.resolve("jeno.toml"));
        } catch (java.io.IOException e) {
            return LockModule.fatal(dir, true, "MANIFEST_MISSING",
                    "no jeno.toml in " + dir + " — run jeno init to create one");
        }
        try {
            onDisk = java.nio.file.Files.readString(dir.resolve("jeno.lock"));
        } catch (java.io.IOException e) {
            return LockModule.fatal(dir, true, "LOCK_MISSING",
                    "no jeno.lock in " + dir + " — run jeno lock");
        }

        LockService.Prepared prepared;
        try {
            prepared = lockService.prepare(manifest);
        } catch (RuntimeException e) {
            return LockModule.fatal(dir, true, "LOCK_RESOLVE_FAILED", e.getMessage());
        }

        if (prepared.lockText().equals(onDisk)) {
            return new LockModule(dir, true, prepared.graph().artifacts().size(),
                    true, List.of(), null, null);
        }
        return new LockModule(dir, true, prepared.graph().artifacts().size(),
                false, difference(onDisk, prepared.lockText()), null, null);
    }

    private LockModule refreshModule(Path dir) {
        try {
            var graph = lockService.refresh(dir);
            return new LockModule(dir, false, graph.artifacts().size(),
                    true, List.of(), null, null);
        } catch (RuntimeException e) {
            return LockModule.fatal(dir, false, "LOCK_REFRESH_FAILED", e.getMessage());
        }
    }

    /** Line-level difference, first ten changed lines: {@code -} disk, {@code +} regenerated. */
    static java.util.List<String> difference(String onDisk, String regenerated) {
        String[] oldLines = onDisk.split("\n", -1);
        String[] newLines = regenerated.split("\n", -1);
        java.util.List<String> lines = new java.util.ArrayList<>();
        int length = Math.max(oldLines.length, newLines.length);
        for (int i = 0; i < length && lines.size() < 10; i++) {
            String before = i < oldLines.length ? oldLines[i] : null;
            String after = i < newLines.length ? newLines[i] : null;
            if (java.util.Objects.equals(before, after)) {
                continue;
            }
            if (before != null) {
                lines.add("- " + before);
            }
            if (after != null) {
                lines.add("+ " + after);
            }
        }
        return lines;
    }

    private static boolean hasModuleOption(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("--module") || arg.startsWith("--module="));
    }

    private record LockModule(Path dir, boolean check, int artifacts, boolean upToDate,
                              List<String> differences, String fatalCode, String fatalMessage) {
        static LockModule fatal(Path dir, boolean check, String code, String message) {
            return new LockModule(dir, check, 0, false, List.of(), code, message);
        }

        boolean ok() {
            return fatalCode == null && (!check || upToDate);
        }
    }
}
