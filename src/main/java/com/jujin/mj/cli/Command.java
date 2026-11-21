package com.jujin.mj.cli;

import java.util.List;

/**
 * One mj command ({@code lock}, {@code build}, {@code run} — the M3 set).
 * Commands are contributed to the container and constructed with injection;
 * {@link CommandRunner} dispatches by name.
 */
public interface Command {

    /** The command word on the command line, e.g. {@code lock}. */
    String name();

    /**
     * Alternate command words that dispatch to the same command — e.g.
     * {@code upgrade} for {@code update}. Defaults to none; aliases are
     * dispatch-only, the usage output lists the primary name.
     */
    default String[] aliases() {
        return new String[0];
    }

    /** The one-line summary the usage output shows. */
    String summary();

    /** Runs the command with its arguments and returns the process exit code. */
    int execute(List<String> args);
}
