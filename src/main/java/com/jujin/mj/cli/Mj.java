package com.jujin.mj.cli;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;

/**
 * mj's entry point: one container per invocation, one command run inside it,
 * a plain exit code out. Failures print their message — the resolver, decoder
 * and store all speak in next-action errors, not stack traces.
 */
public final class Mj {

    private Mj() {}

    public static void main(String[] args) {
        int exit;
        try (Container container = Freeway.create(new MjModule())) {
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
