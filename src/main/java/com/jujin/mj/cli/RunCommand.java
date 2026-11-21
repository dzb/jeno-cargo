package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
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
import java.util.List;

/**
 * {@code mj run &lt;main-class&gt; [args...]}: runs the program on the full
 * resolved closure plus {@code target/classes} — the runtime side of strict
 * visibility (every transitively allowed jar is here, nothing more).
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
    public String summary() {
        return "run a main class on the resolved closure: mj run <main-class> [args...]";
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
            List<String> scriptArgs = new ArrayList<>();
            scriptArgs.add("sh");
            scriptArgs.add("-c");
            scriptArgs.add(scriptCommand);
            for (String arg : rest) {
                scriptArgs.add(arg);
            }
            Process process;
            try {
                process = new ProcessBuilder(scriptArgs).inheritIO().start();
            } catch (IOException e) {
                System.err.println("mj: cannot run script '" + target + "'");
                return 1;
            }
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }

        Path output = projectDir.resolve(OUTPUT);
        if (!Files.isDirectory(output)) {
            System.err.println("mj: " + output + " is missing — run mj build first");
            return 1;
        }

        Path lock = projectDir.resolve("mj.lock");
        if (!Files.exists(lock)) {
            System.err.println("mj: no mj.lock in " + projectDir + " — run mj lock first");
            return 1;
        }
        List<LockedArtifact> locked;
        try {
            locked = LockFileReader.read(Files.readString(lock));
        } catch (IOException e) {
            System.err.println("mj: cannot read mj.lock");
            return 1;
        }

        List<String> classpath = new ArrayList<>();
        classpath.add(output.toString());
        for (LockedArtifact artifact : locked) {
            Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
            if (jar == null) {
                System.err.println("mj: " + artifact.id() + "@" + artifact.version()
                        + " is not in the store — run mj lock");
                return 1;
            }
            classpath.add(jar.toString());
        }

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command).inheritIO().start();
        } catch (IOException e) {
            System.err.println("mj: cannot run java — is a JDK on the PATH?");
            return 1;
        }
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }
}
