package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.store.ArtifactStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * One machine-readable result document.
 *
 * <p>Commands build a result out of plain {@link JsonObject}s and call
 * {@link #emit(int)} exactly once. Human progress must never be written to
 * stdout in JSON mode; the runner only dispatches JSON to commands that declare
 * support, and this type is the common final envelope.
 */
final class JsonResult {

    /** Set by emit(), consumed by CommandRunner to guarantee one document. */
    private static final ThreadLocal<Boolean> EMITTED = ThreadLocal.withInitial(() -> false);

    private final JsonObject root = JsonUtils.object();
    private final JsonArray diagnostics = JsonUtils.array();
    private int exitCode = 0;

    private JsonResult(String command) {
        root.put("schemaVersion", CommandCatalog.SCHEMA_VERSION);
        root.put("kind", "commandResult");
        root.put("command", command);
        root.put("status", "ok");
        root.put("exitCode", 0);
        root.put("dryRun", false);
        root.put("offline", ArtifactStore.isOffline());
        root.put("diagnostics", diagnostics);
    }

    static JsonResult start(String command) {
        return new JsonResult(command);
    }

    static void begin() {
        EMITTED.set(false);
    }

    static boolean emitted() {
        return EMITTED.get();
    }

    static void markEmitted() {
        EMITTED.set(true);
    }

    static JsonResult error(String command, int exitCode, String code, String message) {
        JsonResult result = start(command);
        result.diagnostic("error", code, message, List.of());
        result.finish(exitCode);
        return result;
    }

    JsonResult put(String key, Object value) {
        root.put(key, value);
        return this;
    }

    JsonResult project(Path root, JenoProject project) {
        JsonObject identity = JsonUtils.object();
        identity.put("root", root.toAbsolutePath().normalize().toString());
        identity.put("name", project.name());
        identity.put("version", project.version());
        this.root.put("project", identity);
        return this;
    }

    JsonResult workspace(Path root, String mode) {
        JsonObject workspace = JsonUtils.object();
        workspace.put("root", root.toAbsolutePath().normalize().toString());
        workspace.put("mode", mode);
        this.root.put("workspace", workspace);
        return this;
    }

    JsonResult dryRun(boolean dryRun) {
        root.put("dryRun", dryRun);
        return this;
    }

    JsonResult diagnostic(String severity, String code, String message,
                          List<List<String>> nextActions) {
        JsonObject diagnostic = JsonUtils.object();
        diagnostic.put("severity", severity);
        diagnostic.put("code", code);
        diagnostic.put("message", message);
        JsonArray actions = JsonUtils.array();
        for (List<String> action : nextActions) {
            JsonArray argv = JsonUtils.array();
            action.forEach(argv::add);
            actions.add(argv);
        }
        diagnostic.put("nextActions", actions);
        diagnostics.add(diagnostic);
        return this;
    }

    JsonResult checks(List<CheckResult> checks) {
        JsonArray array = JsonUtils.array();
        for (CheckResult check : checks) {
            array.add(check.json());
        }
        root.put("checks", array);
        return this;
    }

    JsonResult finish(int exitCode) {
        this.exitCode = exitCode;
        root.put("exitCode", exitCode);
        root.put("status", exitCode == 0 ? "ok" : exitCode == 1 ? "failed" : "error");
        return this;
    }

    void emit(int exitCode) {
        finish(exitCode);
        System.out.println(root);
        EMITTED.set(true);
    }

    int exitCode() {
        return exitCode;
    }
}
