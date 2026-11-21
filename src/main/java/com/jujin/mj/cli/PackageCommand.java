package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.project.MjProject;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.LockFileReader;
import com.jujin.mj.store.LockedArtifact;

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
 * {@code mj package} — the packaging verb. Two output formats:
 *
 * <ul>
 *   <li><b>default (dist)</b> — a distribution directory with a launcher,
 *       separate jars, setup.sh, and a tar.gz. Readable, inspectable,
 *       updatable jar-by-jar.</li>
 *   <li><b>--uber</b> — a single fat jar with all closure classes merged,
 *       services combined, signatures dropped. One file, easy to share.</li>
 * </ul>
 *
 * <p>Both write to {@code target/}. The dist directory additionally produces
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
        return "package the project: distribution directory (default) or uber-jar (--uber)";
    }

    @Override
    public int execute(List<String> args) {
        boolean uber = false;
        String mainClass = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--uber")) {
                uber = true;
            } else if (arg.equals("--main")) {
                if (i + 1 >= args.size()) {
                    System.err.println("mj: --main needs the main class name");
                    return 2;
                }
                mainClass = args.get(++i);
            } else {
                System.err.println("mj: unknown package option '" + arg + "'");
                return 2;
            }
        }

        MjProject project;
        List<LockedArtifact> locked;
        Map<String, Path> jars;
        try {
            var mjToml = projectDir.resolve("mj.toml");
            var mjLock = projectDir.resolve("mj.lock");
            if (!Files.exists(mjToml)) {
                System.err.println("mj: cannot read mj.toml in " + projectDir);
                return 1;
            }
            project = ProjectReader.parse(Files.readString(mjToml));
            if (!Files.exists(mjLock)) {
                System.err.println("mj: no mj.lock — run mj lock first");
                return 1;
            }
            locked = LockFileReader.read(Files.readString(mjLock));
            jars = store.verifiedJars(locked);
        } catch (IOException | RuntimeException e) {
            System.err.println("mj: " + e.getMessage());
            return 1;
        }

        if (mainClass == null) {
            mainClass = project.main();
        }

        return uberJar(project, locked, jars, mainClass);
    }

    // ------------------------------------------------------------------
    // dist mode (default)
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // uber mode
    // ------------------------------------------------------------------

    private int uberJar(MjProject project, List<LockedArtifact> locked,
                        Map<String, Path> jars, String mainClass) {
        String name = project.name();
        String version = project.version();
        Path output = projectDir.resolve("target/" + name + "-" + version + ".jar");
        Path classes = projectDir.resolve(CLASSES);

        if (!Files.isDirectory(classes)) {
            System.err.println("mj: " + CLASSES + " is missing — run mj build first");
            return 1;
        }

        try {
            Files.createDirectories(output.getParent());
            try (var out = new java.util.zip.ZipOutputStream(
                    Files.newOutputStream(output))) {
                var manifest = new java.util.jar.Manifest();
                var attrs = manifest.getMainAttributes();
                attrs.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
                if (mainClass != null) {
                    attrs.put(java.util.jar.Attributes.Name.MAIN_CLASS, mainClass);
                }
                attrs.put(new java.util.jar.Attributes.Name("Built-By"), "mj");
                List<String> deps = new ArrayList<>();
                for (LockedArtifact artifact : locked) {
                    deps.add(artifact.id() + "@" + artifact.version());
                }
                attrs.put(new java.util.jar.Attributes.Name("mj-Dependencies"),
                        String.join(" ", deps));
                out.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
                manifest.write(out);
                out.closeEntry();

                try (Stream<Path> stream = Files.walk(classes)) {
                    for (Path file : stream.filter(Files::isRegularFile).toList()) {
                        String entry = classes.relativize(file).toString().replace('\\', '/');
                        writeEntry(out, entry, Files.readAllBytes(file));
                    }
                }

                Set<String> written = new LinkedHashSet<>();
                Map<String, Set<String>> services = new java.util.TreeMap<>();
                for (LockedArtifact artifact : locked) {
                    try (var in = new java.util.jar.JarInputStream(
                            Files.newInputStream(jars.get(artifact.id())))) {
                        java.util.jar.JarEntry entry;
                        while ((entry = in.getNextJarEntry()) != null) {
                            String entryName = entry.getName();
                            if (entry.isDirectory() || entryName.equals("META-INF/MANIFEST.MF")
                                    || isSignature(entryName) || isModuleInfo(entryName)) {
                                continue;
                            }
                            byte[] bytes = in.readAllBytes();
                            if (entryName.startsWith("META-INF/services/")) {
                                services.computeIfAbsent(entryName, key -> new LinkedHashSet<>())
                                        .addAll(serviceLines(bytes));
                                continue;
                            }
                            if (written.add(entryName)) {
                                writeEntry(out, entryName, bytes);
                            }
                        }
                    }
                }
                for (var service : services.entrySet()) {
                    writeEntry(out, service.getKey(),
                            String.join("\n", service.getValue()).getBytes());
                }
            }
        } catch (IOException e) {
            System.err.println("mj: packaging failed: " + e.getMessage());
            return 1;
        }

        System.out.println("packaged " + output + " — uber-jar ("
                + locked.size() + " dependencies merged)"
                + (mainClass != null ? " — java -jar " + output : ""));
        return 0;
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    private static void writeEntry(java.util.zip.ZipOutputStream out, String name,
                                   byte[] bytes) throws IOException {
        out.putNextEntry(new java.util.zip.ZipEntry(name));
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
