package com.jujin.jeno.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches the command word to its {@link Command}: unknown commands and
 * missing commands print the usage line and exit 2 — never a stack trace.
 *
 * <p>The usage listing is laid out like a modern package manager's: each
 * command owns its {@link Command.Category} and ordering, the command column is
 * padded to one width so descriptions line up, aliases appear in the command
 * column as {@code update | upgrade}, and nothing in the description repeats
 * the command name.
 */
public final class CommandRunner {

    /** Widest command column + the alias separator's breathing room. */
    private static final int MIN_COLUMN = 24;

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
        List<String> commandLine = new ArrayList<>(List.of(args));

        // Canonical global options live before the command. The historical
        // after-command forms below remain supported (notably `build --offline`
        // and `tree --json`) for compatibility.
        boolean json = false;
        boolean help = false;
        boolean offline = false;
        boolean version = false;
        for (int i = 0; i < commandLine.size(); ) {
            String arg = commandLine.get(i);
            if (arg.equals("--json") || arg.equals("--format=json")) {
                json = true;
                commandLine.remove(i);
            } else if (arg.equals("--format=text")) {
                json = false;
                commandLine.remove(i);
            } else if (arg.equals("--help") || arg.equals("-h")) {
                help = true;
                commandLine.remove(i);
            } else if (arg.equals("--offline") || arg.equals("-o")) {
                offline = true;
                commandLine.remove(i);
            } else if (arg.equals("--version") || arg.equals("-v")) {
                version = true;
                commandLine.remove(i);
            } else {
                break;
            }
        }

        if (version) {
            if (json) {
                CommandCatalog.printVersion();
            } else {
                System.out.println(Ui.heading("jeno " + Jeno.VERSION)
                        + Ui.dim(" — " + Jeno.TAGLINE));
            }
            return 0;
        }

        if (commandLine.isEmpty()) {
            if (json) {
                if (help) {
                    CommandCatalog.printFull(commands);
                    return 0;  // a request for the listing is not an error
                }
                CommandCatalog.printError("", 2, "MISSING_COMMAND",
                        "no command given; use --json --help for the catalog",
                        List.of(args), false);
                return 2;
            }
            return usage(help ? 0 : 2);
        }

        String commandName = commandLine.getFirst();
        Command command = byName.get(commandName);
        if (command == null) {
            if (json) {
                CommandCatalog.printError(commandName, 2, "UNKNOWN_COMMAND",
                        "unknown command '" + commandName + "'", List.of(args), false);
                return 2;
            }
            System.err.println(Ui.error("unknown command '" + commandName + "'"));
            return usage(2);
        }

        List<String> raw = commandLine.subList(1, commandLine.size());
        List<String> rest = new ArrayList<>();
        List<String> passthrough = new ArrayList<>();
        boolean afterTerminator = false;
        for (String arg : raw) {
            if (!afterTerminator && arg.equals("--")) {
                afterTerminator = true;
                continue;
            }
            (afterTerminator ? passthrough : rest).add(arg);
        }

        int flagsEnd = Math.min(command.flagsEndAt(rest), rest.size());
        boolean localJson = hasJsonFlag(rest, flagsEnd);
        json |= localJson;

        if (help || localHelp(rest, passthrough)) {
            if (json) {
                CommandCatalog.printCommand(command);
            } else {
                printCommandHelp(command);
            }
            return 0;
        }

        if (json && !command.capabilities().supportsJson()) {
            CommandCatalog.printError(command.name(), 2, "JSON_UNSUPPORTED",
                    "command '" + command.name() + "' does not declare JSON output yet; "
                    + "use `jeno --json " + command.name() + " --help` for its descriptor",
                    rest.subList(0, flagsEnd), offline);
            return 2;
        }
        if (json) {
            // The per-command JSON switches predate the global one. Keep them
            // as the command-facing implementation until the output-context
            // refactor lands.
            normalizeJsonFlag(rest, flagsEnd);
            if (!rest.contains("--json")) {
                rest.add("--json");
            }
        }

        // --offline/-o is a property of the run, applied before the command sees
        // its arguments. It is only recognized where this command's global flags
        // live: `run app --offline` gives --offline to the program.
        boolean localOffline = false;
        for (int i = 0; i < flagsEnd; i++) {
            if (rest.get(i).equals("--offline") || rest.get(i).equals("-o")) {
                localOffline = true;
            }
        }
        offline |= localOffline;
        if (offline) {
            if (!command.supportsOffline()) {
                String message = command.name() + " needs the network by design — "
                        + "--offline does not apply";
                if (json) {
                    CommandCatalog.printError(command.name(), 2, "OFFLINE_FORBIDDEN", message,
                            rest.subList(0, flagsEnd), offline);
                } else {
                    System.err.println(Ui.error(message));
                }
                return 2;
            }
            com.jujin.jeno.store.ArtifactStore.goOffline();
            for (int i = flagsEnd - 1; i >= 0; i--) {
                if (rest.get(i).equals("--offline") || rest.get(i).equals("-o")) {
                    rest.remove(i);
                }
            }
        }

        JsonResult.begin();
        int exit;
        try {
            exit = command.execute(List.copyOf(rest), List.copyOf(passthrough));
        } catch (LegacyNames.LegacyException e) {
            if (json) {
                JsonResult.error(command.name(), 1, "LEGACY_NAME", e.getMessage())
                        .status("error").emit(1);
            } else {
                System.err.println(Ui.errPrefix() + e.getMessage());
            }
            return 1;
        } catch (RuntimeException e) {
            if (!json) {
                throw e;
            }
            if (JsonResult.emitted()) {
                return 1;  // stdout already has its one document
            }
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            JsonResult.error(command.name(), 1, "COMMAND_FAILED", message).emit(1);
            return 1;
        }
        if (json && !JsonResult.emitted()) {
            JsonResult.error(command.name(), exit == 0 ? 1 : exit,
                    "COMMAND_RESULT_MISSING",
                    "the command exited without emitting its declared JSON result")
                    .emit(exit == 0 ? 1 : exit);
            return exit == 0 ? 1 : exit;
        }
        return exit;
    }

    /** True when {@code --help} is the only non-JSON option before the terminator. */
    private static boolean localHelp(List<String> rest, List<String> passthrough) {
        if (!passthrough.isEmpty()) {
            return false;  // after --, --help belongs to the command/program
        }
        boolean seen = false;
        for (String arg : rest) {
            if (arg.equals("--help") || arg.equals("-h")) {
                seen = true;
            } else if (!arg.equals("--json") && !arg.equals("--format=json")) {
                return false;
            }
        }
        return seen;
    }

    /** JSON switches anywhere in the command's own flag region. */
    private static boolean hasJsonFlag(List<String> rest, int flagsEnd) {
        for (int i = 0; i < flagsEnd; i++) {
            if (rest.get(i).equals("--json") || rest.get(i).equals("--format=json")) {
                return true;
            }
        }
        return false;
    }

    /** Keep the historical command parsers on the `--json` spelling. */
    private static void normalizeJsonFlag(List<String> rest, int flagsEnd) {
        for (int i = 0; i < flagsEnd; i++) {
            if (rest.get(i).equals("--format=json")) {
                rest.set(i, "--json");
            }
        }
    }

    private static void printCommandHelp(Command command) {
        String syntax = command.usage();
        System.out.println(Ui.heading("usage:") + " jeno " + Ui.accent(command.name())
                + (syntax.isEmpty() ? "" : " " + syntax));
        String[] aliases = command.aliases();
        String summary = command.summary()
                + (aliases.length == 0 ? "" : " (also: " + String.join(", ", aliases) + ")");
        System.out.println("       " + Ui.dim(summary));
        for (String line : command.help().split("\n")) {
            if (!line.isBlank()) {
                System.out.println("       " + Ui.dim(line));
            }
        }
    }

    private int usage(int exit) {
        Map<Command.Category, List<Command>> groups = new LinkedHashMap<>();
        for (Command.Category category : Command.Category.values()) {
            groups.put(category, new ArrayList<>());
        }
        for (Command command : commands) {
            groups.get(command.category()).add(command);
        }

        int column = MIN_COLUMN;
        for (Command command : commands) {
            column = Math.max(column, label(command).length() + 2);
        }

        System.out.println(Ui.heading("jeno " + Jeno.VERSION)
                + Ui.dim(" — " + Jeno.TAGLINE));
        System.out.println();
        System.out.println(Ui.dim("usage:") + " jeno <command> [args]");
        for (Map.Entry<Command.Category, List<Command>> entry : groups.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            System.out.println();
            System.out.println(Ui.heading(entry.getKey().title()));
            List<Command> ordered = entry.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(Command::order)
                            .thenComparing(Command::name))
                    .toList();
            for (Command command : ordered) {
                System.out.println("  " + Ui.accent(pad(label(command), column))
                        + Ui.dim(command.summary()));
            }
        }
        System.out.println();
        System.out.println(Ui.heading("Global flags"));
        System.out.println("  " + pad("--json", column)
                + Ui.dim("machine-readable output; with --help it prints the command catalog"));
        System.out.println("  " + pad("--format=json", column)
                + Ui.dim("long-form alias of --json"));
        System.out.println("  " + pad("-o, --offline", column)
                + Ui.dim("refuse all network access; remote publish/release targets, "
                + "update/outdated/audit are rejected; local targets remain allowed"));
        System.out.println("  " + pad("--", column)
                + Ui.dim("end jeno's options; the rest goes to the command"));
        System.out.println();
        System.out.println(Ui.heading("Examples"));
        for (String example : List.of(
                "jeno init",
                "jeno add \"org.slf4j:slf4j-api\" 2.0.18",
                "jeno build && jeno test",
                "jeno doctor",
                "jeno --json --help")) {
            System.out.println("  " + Ui.dim(example));
        }
        System.out.println();
        System.out.println(Ui.dim("Run 'jeno <command> --help' for command details."));
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
