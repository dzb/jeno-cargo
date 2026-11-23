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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code mjm package} — the packaging verb. Two output formats:
 *
 * <ul>
 *   <li><b>default (dist)</b> — a distribution directory with a launcher,
 *       separate jars, setup.sh, and a tar.gz. Readable, inspectable,
 *       updatable jar-by-jar.</li>
 *   <li><b>--uber</b> — a single fat jar with all closure classes merged,
 *       services combined, signatures dropped. One file, easy to share.</li>
 * </ul>
 *
 * <p>Both write to {@code target/dist/} — the one directory that holds
 * deliverables. {@code target/} keeps only disposable build intermediates
 * ({@code classes}, {@code test-classes}), so {@code rm -rf target} stays the
 * safe, familiar gesture. The dist directory additionally produces
 * a tar.gz (the release artifact) and a setup.sh (self-installing).
 */
public final class PackageCommand implements Command {

    private static final String CLASSES = "target/classes";



    private final ArtifactStore store;
    private final Path projectDir;

    @Inject
    public PackageCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "package";
    }

    @Override
    public String summary() {
        return "build the project jar (--uber merges the closure)";
    }
    @Override
    public String usage() {
        return "[--uber] [--main <class>]";
    }


    @Override
    public Command.Category category() {
        return Command.Category.BUILD;
    }

    @Override
    public int order() {
        return 40;
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
            return JsonWorkspace.run("package", projectDir, cleaned, this::jsonModule);
        }
        return executeHuman(cleaned);
    }

    private JsonWorkspace.ModuleResult jsonModule(Path moduleDir, List<String> args) {
        long started = System.nanoTime();
        JsonCapture.Result captured = JsonCapture.run(() ->
                new PackageCommand(store, moduleDir).executeHuman(args));
        if (captured.exitCode() != 0) {
            return JsonWorkspace.ModuleResult.failed(captured.exitCode(),
                    captured.exitCode() == 2 ? "USAGE" : "PACKAGE_FAILED", captured.message());
        }
        try {
            JenoProject project = ProjectReader.parse(
                    Files.readString(moduleDir.resolve("mjm.toml")));
            boolean uber = args.contains("--uber");
            String main = requestedMain(args, project);
            String fileName = project.name() + "-" + project.version()
                    + (uber ? "-uber" : "") + ".jar";
            Path jar = moduleDir.resolve("target/dist").resolve(fileName)
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar)) {
                return JsonWorkspace.ModuleResult.failed(1, "ARTIFACT_MISSING",
                        "package reported success but did not write " + jar);
            }
            JsonObject data = JsonUtils.object();
            data.put("variant", uber ? "uber" : "plain");
            data.put("durationMs", (System.nanoTime() - started) / 1_000_000L);
            JsonArray artifacts = JsonUtils.array();
            JsonObject artifact = JsonUtils.object();
            artifact.put("kind", "jar");
            artifact.put("variant", uber ? "uber" : "plain");
            artifact.put("path", jar.toString());
            artifact.put("sha256", ArtifactStore.sha256(jar));
            artifact.put("bytes", Files.size(jar));
            if (main != null) {
                artifact.put("mainClass", main);
            }
            artifacts.add(artifact);
            data.put("artifacts", artifacts);
            data.put("project", project.name() + "@" + project.version());
            return JsonWorkspace.ModuleResult.ok(data);
        } catch (IOException | RuntimeException e) {
            return JsonWorkspace.ModuleResult.failed(1, "RESULT_UNREADABLE",
                    "package finished but its result could not be described: " + e.getMessage());
        }
    }

    private static String requestedMain(List<String> args, JenoProject project) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--main") && i + 1 < args.size()) {
                return args.get(i + 1);
            }
        }
        return project.main();
    }

    private int executeHuman(List<String> args) {
        Integer workspace = WorkspaceRun.orchestrate(projectDir, args, "packaged", false,
                (dir, rest) -> new PackageCommand(store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean uber = false;
        String mainClass = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--uber")) {
                uber = true;
            } else if (arg.equals("--main")) {
                if (i + 1 >= args.size()) {
                    System.err.println(Ui.errPrefix() + "--main needs the main class name");
                    return 2;
                }
                mainClass = args.get(++i);
            } else {
                System.err.println(Ui.errPrefix() + "unknown package option '" + arg + "'");
                return 2;
            }
        }

        // package writes target/dist/; clean --dist and the other writers take the
        // same project lock, so a deliverable is never deleted mid-write
        boolean uberJar = uber;
        String requestedMain = mainClass;
        return ProjectLock.with(projectDir, () -> write(requestedMain, uberJar));
    }

    private int write(String mainClass, boolean uber) {
        DistOutput.warnLegacy(projectDir);
        JenoProject project;
        List<LockedArtifact> locked;
        Map<String, Path> jars;
        Substitutions substitutions;
        try {
            var mjToml = projectDir.resolve("mjm.toml");
            var mjLock = projectDir.resolve("mjm.lock");
            if (!Files.exists(mjToml)) {
                System.err.println(Ui.errPrefix() + "no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
                return 1;
            }
            project = ProjectReader.parse(Files.readString(mjToml));
            if (!Files.exists(mjLock)) {
                System.err.println(Ui.errPrefix() + "no mjm.lock — run mjm lock first");
                return 1;
            }
            locked = LockFileReader.read(Files.readString(mjLock));
            substitutions = Substitutions.forProject(projectDir);
            jars = store.verifiedJars(locked,
                    artifact -> substitutions.find(artifact.id()) != null);
        } catch (IOException | RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }

        if (mainClass == null) {
            mainClass = project.main();
        }

        return uber ? uberJar(project, locked, jars, substitutions, mainClass)
                    : plainJar(project, mainClass);
    }

    // ------------------------------------------------------------------
    // dist mode (default)
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // uber mode
    // ------------------------------------------------------------------

    /** Default: the project's own classes, no dependency merging. */
    private int plainJar(JenoProject project, String mainClass) {
        String name = project.name();
        String version = project.version();
        Path output;
        try {
            output = DistOutput.dist(projectDir).resolve(name + "-" + version + ".jar");
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot prepare target/dist/ — " + e.getMessage());
            return 1;
        }
        Path classes = projectDir.resolve(CLASSES);

        if (!Files.isDirectory(classes)) {
            System.err.println(Ui.errPrefix() + CLASSES + " is missing — run mjm build first");
            return 1;
        }

        try {
            DistOutput.clear(output);
            Files.createDirectories(output.getParent());
            try (var out = new java.util.zip.ZipOutputStream(
                    Files.newOutputStream(output))) {
                var manifest = new java.util.jar.Manifest();
                var attrs = manifest.getMainAttributes();
                attrs.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
                if (mainClass != null) {
                    attrs.put(java.util.jar.Attributes.Name.MAIN_CLASS, mainClass);
                }
                attrs.put(new java.util.jar.Attributes.Name("Built-By"), "mjm");
                attrs.put(new java.util.jar.Attributes.Name("Implementation-Title"), name);
                attrs.put(new java.util.jar.Attributes.Name("Implementation-Version"), version);
                out.putNextEntry(ZipEntries.entry("META-INF/MANIFEST.MF"));
                manifest.write(out);
                out.closeEntry();

                try (Stream<Path> stream = Files.walk(classes)) {
                    List<Path> files = stream.filter(Files::isRegularFile)
                            .sorted(java.util.Comparator.comparing(
                                    file -> classes.relativize(file).toString()))
                            .toList();
                    for (Path file : files) {
                        String entry = classes.relativize(file).toString().replace('\\', '/');
                        writeEntry(out, entry, Files.readAllBytes(file));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "packaging failed: " + e.getMessage());
            return 1;
        }

        System.out.println(Ui.ok("packaged " + output
                + " — plain jar (project classes only, use --uber to merge dependencies)"));
        return 0;
    }

    /** --uber: project classes + all closure jars merged into one. */
    private int uberJar(JenoProject project, List<LockedArtifact> locked,
                        Map<String, Path> jars, Substitutions substitutions,
                        String mainClass) {
        String name = project.name();
        String version = project.version();
        // distinct from the plain jar: the two carry different bytes, and one
        // silently overwriting the other means a release ships whichever ran
        // last. The classifier convention is Maven's (-uber), not a new one.
        Path output;
        try {
            output = DistOutput.dist(projectDir).resolve(name + "-" + version + "-uber.jar");
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot prepare target/dist/ — " + e.getMessage());
            return 1;
        }
        Path classes = projectDir.resolve(CLASSES);

        if (!Files.isDirectory(classes)) {
            System.err.println(Ui.errPrefix() + CLASSES + " is missing — run mjm build first");
            return 1;
        }
        // the closure in lock order: a workspace module contributes its classes,
        // everything else its verified jar — never a stale copy of the former
        List<Path> mergeJars = new ArrayList<>();
        List<Path> mergeDirs = new ArrayList<>();
        for (LockedArtifact artifact : locked) {
            if (!artifact.isMain()) {
                continue;
            }
            Substitutions.Module local = substitutions.find(artifact.id());
            if (local != null) {
                mergeDirs.add(substitutions.localClasses(local, artifact.version()));
                substitutions.noteEmbedded(local);
            } else {
                Path jar = jars.get(artifact.id());
                if (jar != null) {
                    mergeJars.add(jar);
                }
            }
        }
        try {
            DistOutput.clear(output);
            UberJar.write(output,
                    new UberJar.Input(classes, mergeJars, mergeDirs, UberJar.labels(locked)),
                    new UberJar.Header(name, version, mainClass));
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "packaging failed: " + e.getMessage());
            return 1;
        }

        System.out.println(Ui.ok("packaged " + output + " — uber-jar ("
                + locked.stream().filter(LockedArtifact::isMain).count() + " dependencies merged, "
                + locked.stream().filter(a -> !a.isMain()).count() + " dev excluded)"
                + (mainClass != null ? " — java -jar " + output : "")));
        return 0;
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    private static void writeEntry(java.util.zip.ZipOutputStream out, String name,
                                   byte[] bytes) throws IOException {
        out.putNextEntry(ZipEntries.entry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static boolean isSignature(String name) {
        return name.startsWith("META-INF/")
                && (name.endsWith(".SF") || name.endsWith(".RSA")
                        || name.endsWith(".DSA") || name.endsWith(".EC"));
    }

    private static boolean isModuleInfo(String name) {
        return name.equals("module-info.class")
                || (name.startsWith("META-INF/versions/")
                        && name.endsWith("/module-info.class"));
    }

    private static Set<String> serviceLines(byte[] bytes) {
        Set<String> lines = new LinkedHashSet<>();
        for (String line : new String(bytes).split("\r?\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    private static String originalName(LockedArtifact artifact) {
        String url = artifact.url();
        String name = url.substring(url.lastIndexOf('/') + 1);
        return name.isBlank() || !name.contains(".")
                ? artifact.sha256().substring(0, 12) + ".jar"
                : name;
    }
}
