package com.jujin.mjm.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches the command word to its {@link Command}: unknown commands and
 * missing commands print the usage line and exit 2 — never a stack trace.
 *
 * <p>The usage listing is laid out like a modern package manager's: the
 * command column is padded to one width so the descriptions line up, aliases
 * appear in the command column as {@code update | upgrade}, and nothing in the
 * description repeats the command name.
 */
public final class CommandRunner {

    /** Widest command column + the alias separator's breathing room. */
    private static final int MIN_COLUMN = 22;

    private final Map<String, Command> byName = new LinkedHashMap<>();
    private final List<Command> commands;

    public CommandRunner(List<Command> commands) {
        this.commands = List.copyOf(commands);
        for (Command command : commands) {
            byName.put(command.name(), command);
            for (String alias : command.aliases()) {
                byName.put(alias, command);
            }
        }
    }

    public int run(String... args) {
        if (args.length == 0) {
            return usage();
        }
        Command command = byName.get(args[0]);
        if (command == null) {
            System.err.println("mjm: unknown command '" + args[0] + "'");
            return usage();
        }
        // `mjm <command> --help` is a request for that command's syntax, not an
        // argument to it — the one convention every CLI shares.
        List<String> rest = List.of(args).subList(1, args.length);

        // --offline/-o is a property of the run, applied before the command sees
        // its arguments: every store access below this point obeys it. The two
        // spellings are interchangeable and may repeat.
        boolean offline = rest.contains("--offline") || rest.contains("-o");
        if (offline) {
            if (!command.supportsOffline()) {
                System.err.println("mjm: " + command.name() + " needs the network by design — "
                        + "--offline does not apply");
                return 2;
            }
            com.jujin.mjm.store.ArtifactStore.goOffline();
            List<String> filtered = new java.util.ArrayList<>(rest);
            filtered.removeIf(flag -> flag.equals("--offline") || flag.equals("-o"));
            rest = filtered;
        }
        if (rest.size() == 1 && (rest.getFirst().equals("--help") || rest.getFirst().equals("-h"))) {
            String syntax = command.usage();
            System.out.println("usage: mjm " + command.name()
                    + (syntax.isEmpty() ? "" : " " + syntax));
            String[] aliases = command.aliases();
            if (aliases.length > 0) {
                System.out.println("       " + command.summary()
                        + " (also: " + String.join(", ", aliases) + ")");
            } else {
                System.out.println("       " + command.summary());
            }
            for (String line : command.help().split("\n")) {
                if (!line.isBlank()) {
                    System.out.println("       " + line);
                }
            }
            return 0;
        }
        return command.execute(rest);
    }

    private int usage() {
        int column = MIN_COLUMN;
        for (Command command : commands) {
            column = Math.max(column, label(command).length() + 2);
        }

        System.out.println("usage: mjm <command> [args]");
        System.out.println();
        for (Command command : commands) {
            System.out.println("  " + pad(label(command), column) + command.summary());
        }
        System.out.println();
        System.out.println("  -o, --offline        refuse all network access (store hits only; "
                + "not for update/publish/outdated/audit)");
        return 2;
    }

    /** {@code lock}, or {@code update | upgrade} when the command has aliases. */
    private static String label(Command command) {
        String[] aliases = command.aliases();
        if (aliases.length == 0) {
            return command.name();
        }
        return command.name() + " | " + String.join(" | ", aliases);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }
}
