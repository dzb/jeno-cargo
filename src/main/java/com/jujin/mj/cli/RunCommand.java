package com.jujin.mj.cli;

import com.jujin.mj.project.MjProject;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.LockFileReader;
import com.jujin.mj.store.LockedArtifact;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code mj run &lt;main-class&gt; [args...]} — runs the program on the full
 * resolved closure plus {@code target/classes}: the runtime side of strict
 * visibility (every transitively allowed jar is here, nothing more).
 *
 * <p>{@code mj run <script-name>} runs a {@code [scripts]} entry through the
 * system shell instead. A script inherits the same computed classpath as
 * environment variables, so it can launch the project without rebuilding it
 * by hand:
 *
 * <pre>
 *   MJ_CLASSPATH   target/classes + every MAIN jar, path-separator joined
 *   MJ_HOME        the project directory
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
        return "<main-class | script> [args...]";
    }

    @Override
    public String summary() {
        return "run a main class or a [scripts] entry on the resolved closure";
    }

    @Override
    public int execute(List<String> args) {
        if (args.isEmpty()) {
            System.err.println("mj: mj run needs a main class or a script name — "
                    + "mj run <main-class> [args...] | mj run <script-name>");
            return 2;
        }
        String target = args.get(0);
        List<String> rest = args.subList(1, args.size());

        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mj.toml")));
        } catch (IOException e) {
            System.err.println("mj: cannot read mj.toml in " + projectDir);
            return 1;
        }

        // A [scripts] key wins over a main class name — scripts are the
        // ergonomic entry point, the main class is the mechanical one.
        String scriptCommand = project.scripts().get(target);
        if (scriptCommand != null) {
            return runScript(target, scriptCommand, rest);
        }

        List<String> classpath;
        try {
            classpath = classpath();
        } catch (RuntimeException e) {
            System.err.println("mj: " + e.getMessage());
            return 1;
        }

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.addAll(args);
        return exec(command, null, "mj run");
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
        environment.put("MJ_HOME", projectDir.toAbsolutePath().toString());
        try {
            List<String> classpath = classpath();
            environment.put("MJ_CLASSPATH", String.join(File.pathSeparator, classpath));
        } catch (RuntimeException e) {
            System.err.println("mj: running '" + name + "' without MJ_CLASSPATH — " + e.getMessage());
        }

        List<String> command = new ArrayList<>();
        command.add("sh");
        command.add("-c");
        command.add(scriptCommand);
        // sh -c <command> <$0> <$1>…: the shell takes the first operand as $0,
        // so a placeholder keeps the caller's arguments at $1 and beyond.
        command.add(name);
        command.addAll(rest);
        return exec(command, environment, "mj run script '" + name + "'");
    }

    // ------------------------------------------------------------------
    // classpath
    // ------------------------------------------------------------------

    /**
     * {@code target/classes} followed by every MAIN jar in the closure.
     * Throws with a next action when the build or the lockfile is missing.
     */
    private List<String> classpath() {
        Path output = projectDir.resolve(OUTPUT);
        if (!Files.isDirectory(output)) {
            throw new IllegalStateException(output + " is missing — run mj build first");
        }
        Path lock = projectDir.resolve("mj.lock");
        if (!Files.exists(lock)) {
            throw new IllegalStateException("no mj.lock in " + projectDir + " — run mj lock first");
        }
        List<LockedArtifact> locked;
        try {
            locked = LockFileReader.read(Files.readString(lock));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mj.lock", e);
        }

        List<String> classpath = new ArrayList<>();
        classpath.add(output.toString());
        for (LockedArtifact artifact : locked) {
            if (!artifact.isMain()) {
                continue;  // [dev-deps] never reaches a production run
            }
            Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
            if (jar == null) {
                throw new IllegalStateException(artifact.id() + "@" + artifact.version()
                        + " is not in the store — run mj lock");
            }
            classpath.add(jar.toString());
        }
        return classpath;
    }

    private int exec(List<String> command, Map<String, String> environment,
                     String what) {
        // a script runs from the project root: relative paths in a script mean
        // the project, exactly as they do when the user runs mj from there
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .inheritIO();
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        try {
            return builder.start().waitFor();
        } catch (IOException e) {
            System.err.println("mj: cannot " + what + " — is a shell/Java on the PATH?");
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }
}
