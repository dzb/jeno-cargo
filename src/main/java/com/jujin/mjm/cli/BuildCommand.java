package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.store.ArtifactStore;
import com.jujin.mjm.store.LockFileReader;
import com.jujin.mjm.store.LockedArtifact;

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
 * {@code mjm build}: compiles {@code src/main/java} into {@code target/classes}
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
        return "compile src/main/java against declared dependencies only";
    }

    @Override
    public int execute(List<String> args) {
        if (!Files.exists(projectDir.resolve("mjm.lock"))) {
            System.err.println("mjm: no mjm.lock in " + projectDir + " — run mjm lock first");
            return 1;
        }
        if (!Files.exists(projectDir.resolve("mjm.toml"))) {
            System.err.println("mjm: cannot read mjm.toml in " + projectDir);
            return 1;
        }
        List<LockedArtifact> locked = readLock();
        MjProject project = readProject();

        // SPEC §2.1: [java].version means (a) produce bytecode for that major
        // via --release, and (b) require the running JDK to be at least that.
        // Skipping (a) would let mj compile against a newer API than the
        // declared target while the published descriptor claims the target —
        // a claim the bytes would not honor.
        if (project.javaVersion() != null) {
            int running = Runtime.version().feature();
            if (running < project.javaVersion()) {
                System.err.println("mjm: this project targets Java "
                        + project.javaVersion() + " but mj runs on Java " + running
                        + " — run mj on a newer JDK, or lower [java].version");
                return 1;
            }
        }

        Map<String, Path> jarById;
        try {
            jarById = store.verifiedJars(locked);
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }

        List<String> compileClasspath = new ArrayList<>();
        for (var requirement : project.requirements()) {
            if (!jarById.containsKey(requirement.id())) {
                System.err.println("mjm: mjm.lock is stale — '" + requirement.id()
                        + "' is declared in mjm.toml but not locked; run mjm lock");
                return 1;
            }
            compileClasspath.add(jarById.get(requirement.id()).toString());
        }
        List<String> sources = sources();
        if (sources.isEmpty()) {
            System.err.println("mjm: no sources under " + projectDir.resolve(SOURCES));
            return 1;
        }

        Path output = projectDir.resolve(OUTPUT);
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            System.err.println("mjm: cannot create " + output);
            return 1;
        }

        // In-process javac: mj runs on a JDK, so the compiler is always here —
        // no PATH dependency, output straight to the console.
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("mjm: no system Java compiler — mj needs a JDK, not a JRE");
            return 1;
        }
        List<String> command = new ArrayList<>();
        command.add("-d");
        command.add(output.toString());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, compileClasspath));
        // full debug info: line numbers, source file, and local variable names
        // — what a debugger and a decompiler need to make `mjm source` trees
        // line up with the bytes
        command.add("-g");
        // annotation processors ride on -processorpath: they are build tools,
        // not an API, so they must never appear on the compile classpath
        List<String> processorPath = new ArrayList<>();
        for (LockedArtifact artifact : locked) {
            if (artifact.scope() == com.jujin.mjm.resolve.Scope.PROCESSOR) {
                processorPath.add(jarById.get(artifact.id()).toString());
            }
        }
        if (!processorPath.isEmpty()) {
            command.add("-processorpath");
            command.add(String.join(File.pathSeparator, processorPath));
        }
        if (project.javaVersion() != null) {
            command.add("--release");
            command.add(String.valueOf(project.javaVersion()));
        }
        command.addAll(project.buildParameters());
        command.addAll(sources);
        int exit = compiler.run(null, null, null, command.toArray(String[]::new));
        if (exit == 0) {
            SourceResources.copy(projectDir.resolve("src/main/resources"), output);
            writeVersionResource(project, output);
            System.out.println("compiled " + sources.size() + " source file(s) into " + output);
        }
        return exit;
    }

    /**
     * Writes {@code META-INF/mjm/version.properties} from mjm.toml — the one
     * declaration becomes a resource the running binary can read from a
     * classes directory, a jar, or an installed dist alike. This is why mj's
     * version is not a source constant: a resource survives every layout,
     * while {@code Package.getImplementationVersion()} is null off a jar.
     */
    private void writeVersionResource(MjProject project, Path output) {
        Path target = output.resolve("META-INF/mjm/version.properties");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, Version.propertiesText(project.version()));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + target, e);
        }
    }

    private List<LockedArtifact> readLock() {
        try {
            return LockFileReader.read(Files.readString(projectDir.resolve("mjm.lock")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mjm.lock", e);
        }
    }

    private MjProject readProject() {
        try {
            return ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mjm.toml in " + projectDir, e);
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
