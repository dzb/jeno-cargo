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
            return usage(2);
        }
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            return usage(0);  // a request for the listing is not an error
        }
        Command command = byName.get(args[0]);
        if (command == null) {
            System.err.println("mjm: unknown command '" + args[0] + "'");
            return usage(2);
        }
        List<String> raw = List.of(args).subList(1, args.length);

        // `--` ends mjm's own options: everything after it is positional and is
        // handed to the command untouched (for `run`, to the program).
        List<String> rest = new java.util.ArrayList<>();
        List<String> passthrough = new java.util.ArrayList<>();
        boolean afterTerminator = false;
        for (String arg : raw) {
            if (!afterTerminator && arg.equals("--")) {
                afterTerminator = true;
                continue;
            }
            (afterTerminator ? passthrough : rest).add(arg);
        }

        // --offline/-o is a property of the run, applied before the command sees
        // its arguments. It is only recognized where this command's global flags
        // live: `run app --offline` gives --offline to the program.
        int flagsEnd = Math.min(command.flagsEndAt(rest), rest.size());
        boolean offline = false;
        for (int i = 0; i < flagsEnd; i++) {
            if (rest.get(i).equals("--offline") || rest.get(i).equals("-o")) {
                offline = true;
            }
        }
        if (offline) {
            if (!command.supportsOffline()) {
                System.err.println("mjm: " + command.name() + " needs the network by design — "
                        + "--offline does not apply");
                return 2;
            }
            com.jujin.mjm.store.ArtifactStore.goOffline();
            for (int i = flagsEnd - 1; i >= 0; i--) {
                if (rest.get(i).equals("--offline") || rest.get(i).equals("-o")) {
                    rest.remove(i);
                }
            }
        }
        // `--help` is mjm's only before `--`, and only when it is the one thing
        // left: after a target it belongs to the program.
        if (passthrough.isEmpty() && rest.size() == 1
                && (rest.getFirst().equals("--help") || rest.getFirst().equals("-h"))) {
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
        return command.execute(List.copyOf(rest), List.copyOf(passthrough));
    }

    private int usage(int exit) {
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
                + "not for update/publish/outdated/audit); before the target, after -- it is "
                + "the program's");
        System.out.println("  --                   end mjm's options; the rest goes to the command");
        return exit;
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
