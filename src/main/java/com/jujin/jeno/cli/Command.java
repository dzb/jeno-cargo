package com.jujin.jeno.cli;

import java.util.List;

/**
 * One jeno command ({@code lock}, {@code build}, {@code run} — the M3 set).
 * Commands are contributed to the container and constructed with injection;
 * {@link CommandRunner} dispatches by name.
 */
public interface Command {

    /** The help-page band a command belongs to; commands own their own metadata. */
    enum Category {
        PROJECT("Project"),
        BUILD("Build & run"),
        INSPECT("Inspect"),
        PUBLISH("Publish"),
        MAINTAIN("Maintain"),
        OTHER("Other");

        private final String title;

        Category(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** The command word on the command line, e.g. {@code lock}. */
    String name();

    /**
     * The help-page grouping for this command. The default keeps custom or
     * test commands out of the curated sections rather than guessing a role.
     */
    default Category category() {
        return Category.OTHER;
    }

    /** Relative position inside the category's help band; lower comes first. */
    default int order() {
        return 100;
    }

    /**
     * What an agent (or shell completion) may rely on before invoking this
     * command: output support, network use, write scopes, workspace policy and
     * safety gates. Every command declares this explicitly.
     */
    CommandCapabilities capabilities();

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
     * Extra help text, printed under the summary by
     * {@code jeno <command> --help} — the rules of a command's output that do
     * not fit on one line. Defaults to empty; each line is indented to match
     * the summary.
     */
    default String help() {
        return "";
    }

    /**
     * Whether {@code --offline} is meaningful for this command. Defaults to
     * true: every command that only reads the store obeys the flag through
     * {@code ArtifactStore.ensure}, with no per-command code. {@code outdated},
     * {@code audit}, and {@code upgrade} override it to false because they are
     * network operations by design. {@code publish}/{@code release} keep the
     * default and perform target-aware checks themselves, so local targets work
     * offline while remote targets fail before upload. {@code add} keeps the
     * default: an explicit version resolves from the store offline, and only its
     * no-value discovery mode reaches the network; that path checks
     * {@code ArtifactStore.isOffline()} itself and exits 2.
     */
    default boolean supportsOffline() {
        return true;
    }

    /** The one-line summary the usage listing shows — it never repeats the name. */
    String summary();

    /**
     * Whether arguments after the target belong to the program ({@code run}).
     * When false, everything the runner passes — including text after
     * {@code --} — is this command's own positional argument, and a strict
     * command rejects it.
     */
    default boolean passThroughArguments() {
        return false;
    }

    /**
     * How far into {@code args} this command's global flags may appear. The
     * runner consumes {@code --offline} only before this index; everything from
     * it on belongs to the command (for {@code run}, to the program). Defaults
     * to the whole list.
     */
    default int flagsEndAt(List<String> args) {
        return args.size();
    }

    /** Runs the command with its arguments and returns the process exit code. */
    int execute(List<String> args);

    /**
     * Runs with the part after a {@code --} kept separate. The default appends
     * it as ordinary positional arguments — a strict command then rejects it,
     * which is exactly the rule "only the runner interprets {@code --}".
     */
    default int execute(List<String> args, List<String> passthrough) {
        if (passthrough.isEmpty()) {
            return execute(args);
        }
        java.util.List<String> combined = new java.util.ArrayList<>(args);
        combined.addAll(passthrough);
        return execute(combined);
    }
}
