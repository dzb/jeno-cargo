package com.jujin.jeno.cli;

/**
 * Terminal-aware presentation for human output.
 *
 * <p>Modern terminals get colour and status glyphs; scripts, CI, test
 * harnesses and pipes keep the exact plain text they already parse. The
 * explicit {@code -Dmjm.color=always|never} system property wins over
 * everything; it exists for programmatic embedding/tests and is never set by
 * mjm itself. Otherwise {@code NO_COLOR} wins over {@code MJM_COLOR}, which
 * wins over automatic terminal detection.
 *
 * <p>JSON commands never call this class: their stdout must stay one pure JSON
 * document.
 */
final class Ui {

    /** Tests and embedding tools redirect System.out; captured output stays plain. */
    private static final java.io.PrintStream ORIGINAL_OUT = System.out;

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";

    private Ui() {}

    /** Whether human presentation should include ANSI colour and glyphs. */
    static boolean enabled() {
        String override = System.getProperty("mjm.color");
        if (override != null) {
            if (override.equalsIgnoreCase("always")) {
                return true;
            }
            if (override.equalsIgnoreCase("never")) {
                return false;
            }
        }
        return enabled(System.getenv("MJM_COLOR"),
                System.getenv().containsKey("NO_COLOR"));
    }

    /** The decision seam; tests can exercise it without mutating the process env. */
    static boolean enabled(String mode, boolean noColor) {
        if (noColor) {
            return false;
        }
        if (mode != null) {
            if (mode.equalsIgnoreCase("always")) {
                return true;
            }
            if (mode.equalsIgnoreCase("never")) {
                return false;
            }
        }
        if ("dumb".equalsIgnoreCase(System.getenv("TERM"))) {
            return false;
        }
        if (System.out != ORIGINAL_OUT) {
            return false;  // a harness captured stdout; ANSI would pollute it
        }
        return System.console() != null;
    }

    /** A successful result line: {@code ✓ text} on a terminal, plain text in pipes. */
    static String ok(String text) {
        return enabled() ? GREEN + "✓ " + text + RESET : text;
    }

    /** A failed result line: {@code ✗ text} on a terminal, plain text in pipes. */
    static String fail(String text) {
        return enabled() ? RED + "✗ " + text + RESET : text;
    }

    /** A warning line: {@code ! text} on a terminal, plain text in pipes. */
    static String warn(String text) {
        return enabled() ? YELLOW + "! " + text + RESET : text;
    }

    /** A next-action error; pipes keep the historical {@code mjm: } prefix. */
    static String error(String text) {
        return enabled() ? RED + "error: " + text + RESET : "mjm: " + text;
    }

    /** The prefix for an error whose body is built separately. */
    static String errPrefix() {
        return enabled() ? RED + "error:" + RESET + " " : "mjm: ";
    }

    /** A doctor-style positive check: {@code ✓ text} or {@code ok: text}. */
    static String check(String text) {
        return enabled() ? GREEN + "✓ " + text + RESET : "ok: " + text;
    }

    /** A doctor-style negative check: {@code ✗ text} or {@code error: text}. */
    static String cross(String text) {
        return enabled() ? RED + "✗ " + text + RESET : "error: " + text;
    }

    /** A section or banner heading. */
    static String heading(String text) {
        return enabled() ? BOLD + text + RESET : text;
    }

    /** Secondary information: paths, timestamps, parenthesised facts. */
    static String dim(String text) {
        return enabled() ? DIM + text + RESET : text;
    }

    /** A command, project or identity accent. */
    static String accent(String text) {
        return enabled() ? CYAN + text + RESET : text;
    }

    /** The bold form used for the selected item in a list. */
    static String strong(String text) {
        return enabled() ? BOLD + text + RESET : text;
    }

    /** A quiet, terminal-only duration suffix for completion lines. */
    static String duration(long startNanos) {
        if (!enabled()) {
            return "";
        }
        long nanos = Math.max(0L, System.nanoTime() - startNanos);
        if (nanos < 1_000_000_000L) {
            long millis = Math.max(1L, nanos / 1_000_000L);
            return DIM + " · " + millis + "ms" + RESET;
        }
        double seconds = nanos / 1_000_000_000.0;
        if (seconds < 60.0) {
            return DIM + " · " + String.format(java.util.Locale.ROOT, "%.2fs", seconds) + RESET;
        }
        long total = Math.round(seconds);
        return DIM + " · " + (total / 60) + "m " + (total % 60) + "s" + RESET;
    }
}
