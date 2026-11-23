package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

/**
 * One stable check in a local diagnostic or pre-release report.
 *
 * <p>{@code id} is a machine code, not a display label. Human output uses
 * {@code message}; JSON exposes both so agents can branch on a stable value
 * without parsing prose.
 */
record CheckResult(String id, Status status, String message, JsonObject details) {

    enum Status {
        OK,
        FAILED,
        SKIPPED
    }

    static CheckResult ok(String id, String message) {
        return new CheckResult(id, Status.OK, message, null);
    }

    static CheckResult ok(String id, String message, JsonObject details) {
        return new CheckResult(id, Status.OK, message, details);
    }

    static CheckResult failed(String id, String message) {
        return new CheckResult(id, Status.FAILED, message, null);
    }

    static CheckResult failed(String id, String message, JsonObject details) {
        return new CheckResult(id, Status.FAILED, message, details);
    }

    static CheckResult skipped(String id, String message) {
        return new CheckResult(id, Status.SKIPPED, message, null);
    }

    boolean failed() {
        return status == Status.FAILED;
    }

    JsonObject json() {
        JsonObject result = JsonUtils.object();
        result.put("id", id);
        result.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
        result.put("message", message);
        if (details != null) {
            result.put("details", details);
        }
        return result;
    }
}
