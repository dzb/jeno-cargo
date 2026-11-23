package com.jujin.mjm.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

/**
 * Runs a command's human path with stdout/stderr captured.
 *
 * <p>This is the bridge for commands whose internals still print human status:
 * JSON mode discards that text and emits a structured artifact report instead.
 * Capturing also disables {@link Ui} colour automatically, so stderr used in a
 * diagnostic is plain and stable.
 */
final class JsonCapture {

    private JsonCapture() {}

    record Result(int exitCode, String out, String err) {
        String message() {
            String text = err.isBlank() ? out : err;
            return text.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElse("command exited with code " + exitCode);
        }
    }

    static Result run(IntSupplier action) {
        return run(action, true);
    }

    /** Capture only stdout; stderr remains live for agent-visible progress/logs. */
    static Result runStdout(IntSupplier action) {
        return run(action, false);
    }

    private static Result run(IntSupplier action, boolean captureErr) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            if (captureErr) {
                System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            }
            int exit = action.getAsInt();
            return new Result(exit, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
