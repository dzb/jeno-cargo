package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * mjm's entry point: one container per invocation, one command run inside it,
 * a plain exit code out. Failures print their message — the resolver, decoder
 * and store all speak in next-action errors, not stack traces.
 *
 * <p><b>Console hygiene.</b> A CLI's stderr belongs to the command, not to the
 * framework it is built on. Freeway announces its logging backend on stderr at
 * container startup (unconditionally), SLF4J announces its provider binding,
 * and the container INFO-logs every module it loads. Those are useful when a
 * server boots and useless-to-harmful when someone runs {@code mjm tree} and
 * pipes the output.
 *
 * <p>So the bootstrap happens behind a closed door: stderr is silenced for the
 * duration of {@code Freeway.create}, then restored before the command runs.
 * Errors thrown during bootstrap are replayed to the real stderr — a broken
 * container must never fail silently.
 */
public final class Mjm {

    /**
     * mjm's own version — derived from the build, never hand-maintained here.
     * The one declaration is {@code mjm.toml [project].version}; the build
     * writes it into {@code META-INF/mjm/version.properties} and the jar
     * manifest (see {@link Version}).
     */
    public static final String VERSION = Version.current();

    private Mjm() {}

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("-v"))) {
            System.out.println("mjm " + VERSION + " — modern java manager");
            return;
        }
        int exit;
        Container container = openQuietly();
        try (container) {
            exit = container.get(CommandRunner.class).run(args);
        } catch (RuntimeException e) {
            System.err.println("mjm: " + describe(e));
            System.exit(1);
            return;
        }
        System.exit(exit);
    }

    /**
     * The message to show for a failure.
     *
     * <p>mjm's own exceptions carry messages written for the user
     * ("cannot read mjm.toml in /x — run mjm init"), while the JDK exceptions
     * they wrap do not ({@code NoSuchFileException.getMessage()} is the path
     * and nothing else, and some have no message at all). Walking to the
     * deepest cause therefore throws away the useful half — the outermost
     * message is the one mjm wrote.
     *
     * <p>So: use the outermost message when it says something, otherwise fall
     * back to the innermost, otherwise the class name.
     */
    private static String describe(Throwable failure) {
        for (Throwable current = failure; current != null; ) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return failure.getClass().getSimpleName();
    }

    /**
     * Builds the container with stderr muted. Freeway's startup banner, SLF4J's
     * provider notice and the container's module INFO line all land in that
     * window; none of them are the command's output.
     *
     * <p>A bootstrap failure is replayed on the real stderr — silencing noise
     * must not silence a diagnosis.
     */
    private static Container openQuietly() {
        PrintStream realErr = System.err;
        Captured captured = new Captured(realErr);
        System.setErr(captured.stream());
        try {
            // An explicit level keeps the container's INFO line out of later
            // command output too (a command that fails should not be prefixed
            // by "Loaded 1 module(s)").
            System.setProperty("freeway.log.level",
                    System.getProperty("freeway.log.level", "WARN"));
            // SLF4J prints its provider-binding notice before any logger exists.
            System.setProperty("slf4j.internal.verbosity",
                    System.getProperty("slf4j.internal.verbosity", "ERROR"));
            return Freeway.create(new MjModule());
        } catch (RuntimeException | Error e) {
            captured.replay();
            throw e;
        } finally {
            System.setErr(realErr);
        }
    }

    /** Swallows stderr during bootstrap, replaying on demand. */
    private static final class Captured {

        private final PrintStream real;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        private final PrintStream swallow = new PrintStream(buffer, true,
                java.nio.charset.StandardCharsets.UTF_8);

        Captured(PrintStream real) {
            this.real = real;
        }

        PrintStream stream() {
            return swallow;
        }

        void replay() {
            swallow.flush();
            String text = buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (!text.isBlank()) {
                real.print(text);
            }
        }
    }
}
