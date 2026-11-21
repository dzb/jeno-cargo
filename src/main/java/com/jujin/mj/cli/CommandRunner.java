package com.jujin.mj.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches the command word to its {@link Command}: unknown commands and
 * missing commands print the usage line and exit 2 — never a stack trace.
 */
public final class CommandRunner {

    private final Map<String, Command> byName = new LinkedHashMap<>();
    private final List<Command> commands;

    public CommandRunner(List<Command> commands) {
        this.commands = List.copyOf(commands);
        for (Command command : commands) {
            byName.put(command.name(), command);
        }
    }

    public int run(String... args) {
        if (args.length == 0) {
            return usage();
        }
        Command command = byName.get(args[0]);
        if (command == null) {
            System.err.println("mj: unknown command '" + args[0] + "'");
            return usage();
        }
        return command.execute(List.of(args).subList(1, args.length));
    }

    private int usage() {
        System.out.println("usage: mj <command> [args]");
        for (Command command : commands) {
            System.out.println("  " + command.name() + "  " + command.summary());
        }
        return 2;
    }
}
