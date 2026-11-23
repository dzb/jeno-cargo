package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
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
    private static final String RESOURCES = "src/main/resources";
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
    public Command.Category category() {
        return Command.Category.BUILD;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.NEVER)
                .writes(CommandCapabilities.WriteScope.TARGET)
                .workspace(CommandCapabilities.WorkspaceMode.AGGREGATE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        boolean json = args.contains("--json");
        List<String> cleaned = args.stream().filter(arg -> !arg.equals("--json")).toList();
        if (json) {
            return JsonWorkspace.run("build", projectDir, cleaned, this::jsonModule);
        }
        return executeHuman(cleaned);
    }

    private JsonWorkspace.ModuleResult jsonModule(Path moduleDir, List<String> args) {
        long started = System.nanoTime();
        JsonCapture.Result captured = JsonCapture.run(() ->
                new BuildCommand(store, moduleDir).executeHuman(args));
        if (captured.exitCode() != 0) {
            return JsonWorkspace.ModuleResult.failed(captured.exitCode(),
                    captured.exitCode() == 2 ? "USAGE" : "BUILD_FAILED", captured.message());
        }
        try {
            JenoProject project = ProjectReader.parse(
                    Files.readString(moduleDir.resolve("mjm.toml")));
            Path classes = moduleDir.resolve(OUTPUT).toAbsolutePath().normalize();
            JsonObject data = JsonUtils.object();
            data.put("classesDir", classes.toString());
            data.put("sourceCount", countFiles(moduleDir.resolve(SOURCES), ".java"));
            data.put("resourceCount", countFiles(moduleDir.resolve(RESOURCES), null));
            data.put("fileCount", countFiles(classes, null));
            data.put("bytes", treeSize(classes));
            data.put("durationMs", (System.nanoTime() - started) / 1_000_000L);
            JsonArray artifacts = JsonUtils.array();
            JsonObject artifact = JsonUtils.object();
            artifact.put("kind", "classes");
            artifact.put("path", classes.toString());
            artifact.put("fileCount", countFiles(classes, null));
            artifact.put("bytes", treeSize(classes));
            artifacts.add(artifact);
            data.put("artifacts", artifacts);
            data.put("project", project.name() + "@" + project.version());
            return JsonWorkspace.ModuleResult.ok(data);
        } catch (IOException | RuntimeException e) {
            return JsonWorkspace.ModuleResult.failed(1, "RESULT_UNREADABLE",
                    "build finished but its result could not be described: " + e.getMessage());
        }
    }

    private int executeHuman(List<String> args) {
        Integer workspace = WorkspaceRun.orchestrate(projectDir, args, "built", false,
                (dir, rest) -> new BuildCommand(store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        if (!args.isEmpty()) {
            System.err.println(Ui.errPrefix() + "build takes no arguments — got '" + args.getFirst() + "'");
            return 2;
        }
        if (!Files.exists(projectDir.resolve("mjm.lock"))) {
            System.err.println(Ui.errPrefix() + "no mjm.lock in " + projectDir + " — run mjm lock first");
            return 1;
        }
        if (!Files.exists(projectDir.resolve("mjm.toml"))) {
            System.err.println(Ui.errPrefix() + "no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        }
        // A build writes target/classes; a test run deletes and refills
        // target/test-classes; a clean removes target/. Serialize them per
        // project so none of them pulls files out from under another.
        return ProjectLock.with(projectDir, this::build);
    }

    /** The locked body of {@link #execute}. */
    private int build() {
        long started = System.nanoTime();
        List<LockedArtifact> locked = readLock();
        JenoProject project = readProject();

        // SPEC §2.1: [java].version means (a) produce bytecode for that major
        // via --release, and (b) require the running JDK to be at least that.
        // Skipping (a) would let mjm compile against a newer API than the
        // declared target while the published descriptor claims the target —
        // a claim the bytes would not honor.
        if (project.javaVersion() != null) {
            int running = Runtime.version().feature();
            if (running < project.javaVersion()) {
                System.err.println(Ui.errPrefix() + "this project targets Java "
                        + project.javaVersion() + " but mjm runs on Java " + running
                        + " — run mjm on a newer JDK, or lower [java].version");
                return 1;
            }
        }

        Substitutions substitutions;
        try {
            substitutions = Substitutions.forProject(projectDir);
            // a workspace module supplies its own classes; every other row must
            // still be present and intact in the store
            store.verifiedJars(locked, artifact -> substitutions.find(artifact.id()) != null);
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }

        String compileClasspath;
        try {
            compileClasspath = ClasspathAssembler.of(ClasspathAssembler.Mode.COMPILE,
                    projectDir, store, substitutions,
                    ClasspathAssembler.Input.compile(project, locked)).joined();
        } catch (ClasspathAssembler.ClasspathException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        List<String> sources = sources();
        boolean resources = hasResources();
        if (sources.isEmpty() && !resources) {
            System.err.println(Ui.errPrefix() + "no sources under " + projectDir.resolve(SOURCES)
                    + " and no resources under " + projectDir.resolve(RESOURCES)
                    + " — nothing to build");
            return 1;
        }

        Path output = projectDir.resolve(OUTPUT);
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot create " + output);
            return 1;
        }

        // A resource-only module is a legal build product: no javac step, but
        // the resources (and the version stamp) still land in target/classes.
        if (sources.isEmpty()) {
            SourceResources.copy(projectDir.resolve(RESOURCES), output);
            writeVersionResource(project, output);
            System.out.println(Ui.ok("copied resources into " + output
                    + " (no Java sources to compile)"));
            return 0;
        }

        // In-process javac: mjm runs on a JDK, so the compiler is always here —
        // no PATH dependency, output straight to the console.
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println(Ui.errPrefix() + "no system Java compiler — mjm needs a JDK, not a JRE");
            return 1;
        }
        List<String> command = new ArrayList<>();
        command.add("-d");
        command.add(output.toString());
        command.add("-cp");
        command.add(compileClasspath);
        // full debug info: line numbers, source file, and local variable names
        // — what a debugger and a decompiler need to make `mjm source` trees
        // line up with the bytes
        command.add("-g");
        // annotation processors ride on -processorpath: they are build tools,
        // not an API, so they must never appear on the compile classpath
        List<String> processorPath = new ArrayList<>();
        for (LockedArtifact artifact : locked) {
            if (artifact.scope() == com.jujin.jeno.resolve.Scope.PROCESSOR) {
                processorPath.add(ClasspathAssembler.artifactPath(
                        artifact, store, substitutions, false).toString());
            }
        }
        if (!processorPath.isEmpty()) {
            command.add("-processorpath");
            command.add(ClasspathAssembler.join(processorPath));
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
            System.out.println(Ui.ok("compiled " + sources.size()
                    + " source file(s) into " + output) + Ui.duration(started));
        }
        return exit;
    }

    /**
     * Writes {@code META-INF/mjm/version.properties} from mjm.toml — the one
     * declaration becomes a resource the running binary can read from a
     * classes directory, a jar, or an installed dist alike. This is why mjm's
     * version is not a source constant: a resource survives every layout,
     * while {@code Package.getImplementationVersion()} is null off a jar.
     */
    /** Whether {@code src/main/resources} holds any file at all. */
    private boolean hasResources() {
        Path root = projectDir.resolve(RESOURCES);
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    private static long countFiles(Path root, String suffix) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> suffix == null || path.toString().endsWith(suffix))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long treeSize(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private void writeVersionResource(JenoProject project, Path output) {
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

    private JenoProject readProject() {
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
