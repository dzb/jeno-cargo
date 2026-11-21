package com.jujin.mj.cli;

import com.jujin.freeway.flow.FlowModule;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;

/**
 * mj's entry point: one container per invocation, one command run inside it,
 * a plain exit code out. Failures print their message — the resolver, decoder
 * and store all speak in next-action errors, not stack traces.
 */
public final class Mj {

    /**
     * mj's own version — derived from the build, never hand-maintained here.
     * The one declaration is {@code mj.toml [project].version}; the build
     * writes it into {@code META-INF/mj/version.properties} and the jar
     * manifest (see {@link Version}).
     */
    public static final String VERSION = Version.current();

    private Mj() {}

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("-v"))) {
            System.out.println("mj " + VERSION + " — modern java manager");
            return;
        }
        int exit;
        try (Container container = Freeway.create(new MjModule(), new FlowModule())) {
            exit = container.get(CommandRunner.class).run(args);
        } catch (RuntimeException e) {
            // The deepest cause carries the next action; the wrappers only
            // carry the phase ("unable to construct", "cannot store", ...).
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("mj: " + cause.getMessage());
            System.exit(1);
            return;
        }
        System.exit(exit);
    }
}
