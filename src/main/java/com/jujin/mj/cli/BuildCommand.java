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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code mj build}: compiles {@code src/main/java} into {@code target/classes}
 * against the compile classpath — the declared dependencies only (DESIGN.md
 * §4.4's strict visibility: importing anything undeclared is a compile error
 * by construction). The lockfile is the sole input; every jar is verified
 * against its pinned sha256 before a single source file is read.
 */
public final class BuildCommand implements Command {

    private static final String SOURCES = "src/main/java";
    private static final String OUTPUT = "target/classes";

    private final ArtifactStore store;
    private final Path projectDir;

    public BuildCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String summary() {
        return "compile src/main/java against the declared dependencies (strict visibility)";
    }

    @Override
    public int execute(List<String> args) {
        if (!Files.exists(projectDir.resolve("mj.lock"))) {
            System.err.println("mj: no mj.lock in " + projectDir + " — run mj lock first");
            return 1;
        }
        if (!Files.exists(projectDir.resolve("mj.toml"))) {
            System.err.println("mj: cannot read mj.toml in " + projectDir);
            return 1;
        }
        List<LockedArtifact> locked = readLock();
        MjProject project = readProject();

        Map<String, Path> jarById;
        try {
            jarById = store.verifiedJars(locked);
        } catch (RuntimeException e) {
            System.err.println("mj: " + e.getMessage());
            return 1;
        }

        List<String> compileClasspath = new ArrayList<>();
        for (var requirement : project.requirements()) {
            if (!jarById.containsKey(requirement.id())) {
                System.err.println("mj: mj.lock is stale — '" + requirement.id()
                        + "' is declared in mj.toml but not locked; run mj lock");
                return 1;
            }
            compileClasspath.add(jarById.get(requirement.id()).toString());
        }
        List<String> sources = sources();
        if (sources.isEmpty()) {
            System.err.println("mj: no sources under " + projectDir.resolve(SOURCES));
            return 1;
        }

        Path output = projectDir.resolve(OUTPUT);
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            System.err.println("mj: cannot create " + output);
            return 1;
        }

        // In-process javac: mj runs on a JDK, so the compiler is always here —
        // no PATH dependency, output straight to the console.
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("mj: no system Java compiler — mj needs a JDK, not a JRE");
            return 1;
        }
        List<String> command = new ArrayList<>();
        command.add("-d");
        command.add(output.toString());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, compileClasspath));
        command.addAll(project.buildParameters());
        command.addAll(sources);
        int exit = compiler.run(null, null, null, command.toArray(String[]::new));
        if (exit == 0) {
            SourceResources.copy(projectDir.resolve("src/main/resources"), output);
            System.out.println("compiled " + sources.size() + " source file(s) into " + output);
        }
        return exit;
    }

    private List<LockedArtifact> readLock() {
        try {
            return LockFileReader.read(Files.readString(projectDir.resolve("mj.lock")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mj.lock", e);
        }
    }

    private MjProject readProject() {
        try {
            return ProjectReader.parse(Files.readString(projectDir.resolve("mj.toml")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mj.toml in " + projectDir, e);
        }
    }

    private List<String> sources() {
        Path root = projectDir.resolve(SOURCES);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk " + root, e);
        }
    }
}
