package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared JSON-mode aggregation for commands that work in dependency order at a
 * workspace root. Each module produces one result object; the parent emits one
 * stable document and one summary.
 */
final class JsonWorkspace {

    interface Child {
        ModuleResult run(Path moduleDir, List<String> args);
    }

    record ModuleResult(int exitCode, JsonObject result, String code, String message) {
        static ModuleResult ok(JsonObject result) {
            return new ModuleResult(0, result, null, null);
        }

        static ModuleResult failed(int exitCode, String code, String message) {
            return new ModuleResult(exitCode, null, code, message);
        }

        static ModuleResult completed(int exitCode, JsonObject result,
                                      String code, String message) {
            return new ModuleResult(exitCode, result, code, message);
        }

        boolean ok() {
            return exitCode == 0;
        }
    }

    private JsonWorkspace() {}

    /** One JSON document for single-project and workspace invocations alike. */
    static int run(String command, Path projectDir, List<String> args, Child child) {
        WorkspaceRun.Run run;
        try {
            run = WorkspaceRun.root(projectDir, args);
        } catch (Workspaces.UsageException e) {
            JsonResult.error(command, 2, "USAGE", e.getMessage()).emit(2);
            return 2;
        } catch (Workspaces.WorkspaceException e) {
            JsonResult.error(command, 1, "WORKSPACE_INVALID", e.getMessage()).emit(1);
            return 1;
        }

        Path root;
        String mode;
        List<Path> modules;
        List<String> moduleArgs;
        if (run == null) {
            if (hasModuleOption(args)) {
                JsonResult.error(command, 2, "MODULE_WITHOUT_WORKSPACE",
                        "--module is only used at a workspace root").emit(2);
                return 2;
            }
            root = projectDir.toAbsolutePath().normalize();
            mode = "single";
            modules = List.of(root);
            moduleArgs = args;
        } else {
            root = run.workspace().root();
            mode = run.module() != null ? "module" : "all";
            moduleArgs = run.args();
            try {
                modules = run.module() != null
                        ? List.of(run.module()) : Workspaces.topological(run.workspace());
            } catch (Workspaces.WorkspaceException e) {
                JsonResult.error(command, 1, "WORKSPACE_INVALID", e.getMessage()).emit(1);
                return 1;
            }
        }

        JsonResult result = JsonResult.start(command).workspace(root, mode);
        JsonArray moduleArray = JsonUtils.array();
        int failed = 0;
        boolean usage = false;
        for (Path module : modules) {
            String label = mode.equals("single") ? "." : Workspaces.display(root, module);
            ModuleResult moduleResult = child.run(module, moduleArgs);
            JsonObject entry = JsonUtils.object();
            entry.put("module", label);
            entry.put("path", module.toAbsolutePath().normalize().toString());
            entry.put("status", moduleResult.ok() ? "ok" : "failed");
            entry.put("exitCode", moduleResult.exitCode());
            if (moduleResult.result() != null) {
                entry.put("result", moduleResult.result());
            }
            if (!moduleResult.ok()) {
                failed++;
                usage |= moduleResult.exitCode() == 2;
                result.diagnostic("error",
                        moduleResult.code() == null ? "MODULE_FAILED" : moduleResult.code(),
                        moduleResult.message() == null ? "module failed" : moduleResult.message(),
                        List.of());
            }
            moduleArray.add(entry);
        }
        result.put("modules", moduleArray);

        JsonObject summary = JsonUtils.object();
        summary.put("modules", modules.size());
        summary.put("passedModules", modules.size() - failed);
        summary.put("failedModules", failed);
        result.put("summary", summary);

        int exit = failed == 0 ? 0 : usage ? 2 : 1;
        result.emit(exit);
        return exit;
    }

    private static boolean hasModuleOption(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("--module") || arg.startsWith("--module="));
    }
}
