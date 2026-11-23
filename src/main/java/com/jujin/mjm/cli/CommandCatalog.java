package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.mjm.store.ArtifactStore;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Machine-readable command discovery.
 *
 * <p>{@code mjm --json --help} returns the complete catalog; {@code mjm
 * --json <command> --help} returns one descriptor. The catalog is generated
 * from the same {@link CommandCapabilities} that command dispatch consults, so
 * agent-facing metadata cannot drift into a hand-maintained second list.
 */
final class CommandCatalog {

    static final int SCHEMA_VERSION = 1;

    private CommandCatalog() {}

    /** The whole discovery document, with commands in stable help order. */
    static void printFull(List<Command> commands) {
        var root = JsonUtils.object();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("kind", "commandCatalog");
        root.put("mjmVersion", Mjm.VERSION);

        var globalFlags = JsonUtils.array();
        addFlag(globalFlags, "--json",
                "request machine-readable JSON for help or a command that declares supportsJson");
        addFlag(globalFlags, "--format=json",
                "long-form alias of --json");
        addFlag(globalFlags, "--help, -h",
                "print human help, or the JSON catalog when combined with --json");
        addFlag(globalFlags, "--version, -v",
                "print the mjm version; combine with --json for a JSON document");
        addFlag(globalFlags, "--offline, -o",
                "refuse network access for commands that support it");
        addFlag(globalFlags, "--",
                "end mjm's options; the rest goes to the command");
        root.put("globalFlags", globalFlags);

        var exitCodes = JsonUtils.object();
        exitCodes.put("0", "success");
        exitCodes.put("1", "command result was negative");
        exitCodes.put("2", "usage error, unsupported capability, or safety refusal");
        root.put("exitCodes", exitCodes);

        var entries = JsonUtils.array();
        for (Command command : ordered(commands)) {
            entries.add(descriptor(command));
        }
        root.put("commandCount", entries.size());
        root.put("commands", entries);
        System.out.println(root);
    }

    /** One command descriptor, used by {@code mjm --json <command> --help}. */
    static void printCommand(Command command) {
        var root = JsonUtils.object();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("kind", "commandDescriptor");
        root.put("mjmVersion", Mjm.VERSION);
        root.put("command", descriptor(command));
        System.out.println(root);
    }

    /** Version information for `mjm --json --version`. */
    static void printVersion() {
        var root = JsonUtils.object();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("kind", "version");
        root.put("mjmVersion", Mjm.VERSION);
        root.put("javaVersion", Runtime.version().feature());
        root.put("javaVendor", System.getProperty("java.vendor", ""));
        System.out.println(root);
    }

    /** Minimal structured error for failures that happen before a command runs. */
    static void printError(String command, int exitCode, String code, String message,
                           List<String> argv, boolean effectiveOffline) {
        boolean dryRun = false;
        boolean offline = effectiveOffline || ArtifactStore.isOffline();
        for (String arg : argv) {
            if (arg.equals("--")) {
                break;
            }
            if (arg.equals("--dry-run")) {
                dryRun = true;
            }
            if (arg.equals("--offline") || arg.equals("-o")) {
                offline = true;
            }
        }

        var root = JsonUtils.object();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("kind", "commandResult");
        root.put("command", command == null ? "" : command);
        root.put("status", "error");
        root.put("exitCode", exitCode);
        root.put("dryRun", dryRun);
        root.put("offline", offline);

        var diagnostics = JsonUtils.array();
        var diagnostic = JsonUtils.object();
        diagnostic.put("severity", "error");
        diagnostic.put("code", code);
        diagnostic.put("message", message);
        var nextActions = JsonUtils.array();
        diagnostic.put("nextActions", nextActions);
        diagnostics.add(diagnostic);
        root.put("diagnostics", diagnostics);
        System.out.println(root);
    }

    private static void addFlag(com.jujin.freeway.commons.json.JsonArray flags,
                                String name, String description) {
        var flag = JsonUtils.object();
        flag.put("name", name);
        flag.put("description", description);
        flags.add(flag);
    }

    private static Object descriptor(Command command) {
        CommandCapabilities capabilities = command.capabilities();
        var entry = JsonUtils.object();
        entry.put("name", command.name());

        var aliases = JsonUtils.array();
        for (String alias : command.aliases()) {
            aliases.add(alias);
        }
        entry.put("aliases", aliases);

        entry.put("category", lower(command.category().name()));
        entry.put("categoryTitle", command.category().title());
        entry.put("summary", command.summary());
        entry.put("usage", command.usage());
        if (!command.help().isBlank()) {
            entry.put("help", command.help());
        }

        entry.put("supportsJson", capabilities.supportsJson());
        entry.put("network", lower(capabilities.network().name()));
        var writes = JsonUtils.array();
        capabilities.writes().forEach(scope -> writes.add(lower(scope.name())));
        entry.put("writes", writes);
        entry.put("workspace", lower(capabilities.workspace().name()));
        entry.put("supportsDryRun", capabilities.supportsDryRun());
        entry.put("destructive", capabilities.destructive());
        entry.put("confirmation", lower(capabilities.confirmation().name()));
        entry.put("passThrough", capabilities.passThrough());
        entry.put("interactive", capabilities.interactive());
        return entry;
    }

    private static List<Command> ordered(List<Command> commands) {
        return commands.stream()
                .sorted(Comparator.comparingInt((Command command) ->
                                command.category().ordinal())
                        .thenComparingInt(Command::order)
                        .thenComparing(Command::name))
                .toList();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
