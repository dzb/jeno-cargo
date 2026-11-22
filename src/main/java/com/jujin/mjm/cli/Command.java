package com.jujin.mjm.cli;

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
     * dispatch-only, and the usage listing shows them in the command column
     * ({@code update | upgrade}).
     */
    default String[] aliases() {
        return new String[0];
    }

    /**
     * The argument syntax after the command name, for the per-command usage
     * line — {@code <main-class> [args...]}, {@code <id> [--source]}, or the
     * empty string when the command takes no arguments. Defaults to the empty
     * string.
     */
    default String usage() {
        return "";
    }

    /**
     * Whether {@code --offline} is meaningful for this command. Defaults to
     * true: every command that only reads the store obeys the flag through
     * {@code ArtifactStore.ensure}, with no per-command code. {@code update}
     * and {@code publish} override it to false — their entire purpose is a
     * network call, so accepting the flag would only promise something they
     * cannot do. {@code add} keeps the default: an explicit version resolves
     * from the store offline, and only its no-value "discover" form (the
     * version list or the index search) reaches the network — that path checks
     * {@code ArtifactStore.isOffline()} itself and exits 2.
     */
    default boolean supportsOffline() {
        return true;
    }

    /** The one-line summary the usage listing shows — it never repeats the name. */
    String summary();

    /** Runs the command with its arguments and returns the process exit code. */
    int execute(List<String> args);
}
