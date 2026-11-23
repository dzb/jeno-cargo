package com.jujin.jeno.cli;

import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jeno run &lt;main-class&gt; [args...]} — runs the program on the full
 * resolved closure plus {@code target/classes}: the runtime side of strict
 * visibility (every transitively allowed jar is here, nothing more). With no
 * argument it runs {@code [project].main}, the same entry {@code package}
 * writes into the manifest and {@code bundle} into the launcher.
 *
 * <p>{@code jeno run <script-name>} runs a {@code [scripts]} entry through the
 * system shell instead. A script inherits the same computed classpath as
 * environment variables, so it can launch the project without rebuilding it
 * by hand:
 *
 * <pre>
 *   JENO_CLASSPATH   target/classes + every MAIN jar, path-separator joined
 *   JENO_HOME        the project directory
 * </pre>
 *
 * <p>Extra arguments after the script name arrive as {@code $1}, {@code $2}…
 * (the shell's {@code $0} is the script name), so a script reads them with
 * {@code "$@"} as usual.
 */
public final class RunCommand implements Command {

    private static final String OUTPUT = "target/classes";

    private final ArtifactStore store;
    private final Path projectDir;

    public RunCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String usage() {
        return "[<main-class | script> [args...]]";
    }

    @Override
    public String help() {
        return """
               a [scripts] name wins over a main class of the same name.
               jeno's flags (--module, --offline) are read only before the target;
               after it they are the program's arguments.
               -- ends jeno's options; everything after it goes to the program.""";
    }

    @Override
    public String summary() {
        return "run a main class or a [scripts] entry, or [project].main when no"
                + " argument is given";
    }

    @Override
    public boolean passThroughArguments() {
        return true;
    }

    /**
     * jeno's own flags end at the target: {@code run --module x --offline app}
     * has its flags before {@code app}, and {@code run app --offline} gives
     * {@code --offline} to the program.
     */
    @Override
    public int flagsEndAt(List<String> args) {
        int i = 0;
        while (i < args.size()) {
            String arg = args.get(i);
            if (arg.equals("--module")) {
                i += 2;
                continue;
            }
            if (arg.startsWith("--module=")) {
                i++;
                continue;
            }
            if (arg.startsWith("-") && !arg.equals("-")) {
                i++;
                continue;
            }
            return i;
        }
        return args.size();
    }

    public int execute(List<String> args) {
        return execute(args, List.of());
    }

    @Override
    public Command.Category category() {
        return Command.Category.BUILD;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .network(CommandCapabilities.NetworkMode.NEVER)
                .workspace(CommandCapabilities.WorkspaceMode.MODULE)
                .passThrough()
                .build();
    }

    @Override
    public int execute(List<String> args, List<String> passthrough) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args, true,
                (dir, rest) -> new RunCommand(store, dir).execute(rest, passthrough));
        if (workspace != null) {
            return workspace;
        }
        // The file is read first: in a directory with no jeno.toml, "no jeno.toml"
        // is the real problem, with or without arguments — the usage hint would
        // send the user looking for a main class that cannot exist yet.
        JenoProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("jeno.toml")));
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "no jeno.toml in " + projectDir
                    + " — run jeno init to create one");
            return 1;
        }

        String target;
        List<String> rest;
        if (args.isEmpty()) {
            target = project.main();
            rest = List.copyOf(passthrough);
            if (target == null || target.isBlank()) {
                System.err.println(Ui.errPrefix() + "run needs a main class or a script name — "
                        + "jeno run <main-class> [args...] | jeno run <script-name>, or set "
                        + "[project].main in jeno.toml");
                return 2;
            }
        } else {
            target = args.get(0);
            rest = new ArrayList<>(args.subList(1, args.size()));
            rest.addAll(passthrough);
            rest = List.copyOf(rest);
        }

        // A [scripts] key wins over a main class name — scripts are the
        // ergonomic entry point, the main class is the mechanical one.
        String scriptCommand = project.scripts().get(target);
        if (scriptCommand != null) {
            return runScript(target, scriptCommand, rest);
        }

        String classpath;
        try {
            classpath = classpath();
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-cp");
        command.add(classpath);
        command.add(target);
        command.addAll(rest);
        return exec(command, null, "jeno run");
    }

    // ------------------------------------------------------------------
    // [scripts]
    // ------------------------------------------------------------------

    private int runScript(String name, String scriptCommand, List<String> rest) {
        // The classpath is handed to the script rather than imposed on it: a
        // script may launch java itself, run a test tool, or ignore this
        // entirely. Absent a lockfile or build output the variables are simply
        // not set — a script that does not need them still runs.
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JENO_HOME", projectDir.toAbsolutePath().toString());
        try {
            String classpath = classpath();
            environment.put("JENO_CLASSPATH", classpath);
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + "running '" + name + "' without JENO_CLASSPATH — " + e.getMessage());
        }

        List<String> command = new ArrayList<>();
        command.add("sh");
        command.add("-c");
        command.add(scriptCommand);
        // sh -c <command> <$0> <$1>…: the shell takes the first operand as $0,
        // so a placeholder keeps the caller's arguments at $1 and beyond.
        command.add(name);
        command.addAll(rest);
        return exec(command, environment, "jeno run script '" + name + "'");
    }

    // ------------------------------------------------------------------
    // classpath
    // ------------------------------------------------------------------

    /**
     * {@code target/classes} followed by every MAIN jar in the closure.
     * Throws with a next action when the build or the lockfile is missing.
     */
    private String classpath() {
        Path output = projectDir.resolve(OUTPUT);
        if (!Files.isDirectory(output)) {
            throw new IllegalStateException(output + " is missing — run jeno build first");
        }
        Path lock = projectDir.resolve("jeno.lock");
        if (!Files.exists(lock)) {
            throw new IllegalStateException("no jeno.lock in " + projectDir + " — run jeno lock first");
        }
        List<LockedArtifact> locked;
        try {
            locked = LockFileReader.read(Files.readString(lock));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read jeno.lock", e);
        }

        return ClasspathAssembler.of(ClasspathAssembler.Mode.RUN, projectDir, store,
                ClasspathAssembler.Input.run(locked)).joined();
    }

    private int exec(List<String> command, Map<String, String> environment,
                     String what) {
        // a script runs from the project root: relative paths in a script mean
        // the project, exactly as they do when the user runs jeno from there
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .inheritIO();
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        try {
            return builder.start().waitFor();
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot " + what + " — is a shell/Java on the PATH?");
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }
}
