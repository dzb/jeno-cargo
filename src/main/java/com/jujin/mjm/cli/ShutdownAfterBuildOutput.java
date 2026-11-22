package com.jujin.mjm.cli;

/**
 * Thrown by a command that removed its own build output and must therefore
 * not run the container's shutdown hooks afterwards.
 *
 * <p>When mjm runs from {@code target/classes} — the normal development
 * layout — {@code mjm clean} deletes the directory this very JVM loaded its
 * classes from. The JVM keeps working (loaded classes stay loaded), but
 * Freeway's container shutdown reflects over its beans to call
 * {@code @PreDestroy} methods, and the class loader may try to re-read a class
 * file that is now gone: "Unable to invoke @PreDestroy".
 *
 * <p>Process exit is the honest response: the work is done, the output is
 * deleted as asked, and the exit code is already decided. Running reflective
 * teardown against deleted files is the only thing left to skip.
 */
final class ShutdownAfterBuildOutput extends RuntimeException {

    final int exitCode;

    ShutdownAfterBuildOutput(int exitCode) {
        super("build output removed; skipping container shutdown", null, false, false);
        this.exitCode = exitCode;
    }
}
