package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.project.MjProject;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.LockFileReader;
import com.jujin.mj.store.LockedArtifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

/**
 * {@code mj package [--main &lt;class&gt;]}: the uber-jar, built in — no shade
 * or assembly plugin, because the dependency closure is already resolved and
 * sha256-pinned (DESIGN.md: 内置零依赖). The merge rules are the ones that
 * make fat jars correct, not just fat:
 *
 * <ul>
 *   <li>the manifest is mj's own — Main-Class when given, the full
 *       {@code mj-Dependencies} list always (the {@code go version -m} fact)</li>
 *   <li>{@code META-INF/services} merge line-by-line — ServiceLoader
 *       registrations (logging providers, JDBC drivers) survive the merge</li>
 *   <li>signature files ({@code *.SF} / {@code *.RSA} / {@code *.DSA}) are
 *       dropped — a merged jar cannot honor them</li>
 *   <li>{@code module-info.class} (including {@code META-INF/versions/*}) is
 *       dropped — automatic modules do not survive a merge</li>
 *   <li>every other duplicate: first wins</li>
 * </ul>
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
        return "package a target — your project as uber-jar (default), or mj itself: "
                + "mj package [--main <class>] | mj package self";
    }

    @Override
    public int execute(List<String> args) {
        if (!args.isEmpty() && args.getFirst().equals("self")) {
            return new SelfPackageCommand(store, projectDir).execute(args.subList(1, args.size()));
        }
        String mainClass = null;
        for (int i = 0; i < args.size(); i++) {
            if (!args.get(i).equals("--main")) {
                System.err.println("mj: unknown package option '" + args.get(i) + "'");
                return 2;
            }
            if (i + 1 >= args.size()) {
                System.err.println("mj: --main needs the main class name");
                return 2;
            }
            mainClass = args.get(++i);
        }

        if (!Files.exists(projectDir.resolve("mj.lock"))) {
            System.err.println("mj: no mj.lock in " + projectDir + " — run mj lock first");
            return 1;
        }
        if (!Files.isDirectory(projectDir.resolve(CLASSES))) {
            System.err.println("mj: " + CLASSES + " is missing — run mj build first");
            return 1;
        }

        MjProject project;
        List<LockedArtifact> locked;
        Map<String, Path> jars;
        try {
            project = ProjectReader.parse(
                    Files.readString(projectDir.resolve("mj.toml")));
            locked = LockFileReader.read(
                    Files.readString(projectDir.resolve("mj.lock")));
            jars = store.verifiedJars(locked);
        } catch (IOException | RuntimeException e) {
            System.err.println("mj: " + e.getMessage());
            return 1;
        }

        Path output = projectDir.resolve("target/" + project.name() + "-"
                + project.version() + ".jar");
        try {
            Files.createDirectories(output.getParent());
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
                writeManifest(out, project, locked, mainClass);
                writeClasses(out);
                writeClosure(out, jars.values());
            }
        } catch (IOException e) {
            System.err.println("mj: cannot write the uber-jar: " + e.getMessage());
            return 1;
        }
        System.out.println("packaged " + output + " — " + locked.size()
                + " dependency(ies) merged into one runnable jar"
                + (mainClass != null ? " (java -jar " + output + ")" : ""));
        return 0;
    }

    // ------------------------------------------------------------------
    // the merge
    // ------------------------------------------------------------------

    private void writeManifest(ZipOutputStream out, MjProject project,
                               List<LockedArtifact> locked, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        attributes.put(new Attributes.Name("Built-By"), "mj");
        List<String> dependencies = new ArrayList<>();
        for (LockedArtifact artifact : locked) {
            dependencies.add(artifact.id() + "@" + artifact.version());
        }
        attributes.put(new Attributes.Name("mj-Dependencies"),
                String.join(" ", dependencies));
        attributes.put(new Attributes.Name("mj-Project"),
                project.name() + "@" + project.version());
        out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
        manifest.write(out);
        out.closeEntry();
    }

    private void writeClasses(ZipOutputStream out) throws IOException {
        Path classes = projectDir.resolve(CLASSES);
        try (Stream<Path> stream = Files.walk(classes)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String entry = classes.relativize(file).toString().replace('\\', '/');
                writeEntry(out, entry, Files.readAllBytes(file));
            }
        }
    }

    private void writeClosure(ZipOutputStream out, Iterable<Path> jars) throws IOException {
        Set<String> written = new HashSet<>();
        Map<String, Set<String>> services = new TreeMap<>();
        for (Path jar : jars) {
            try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
                JarEntry entry;
                while ((entry = in.getNextJarEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF")
                            || isSignature(name) || isModuleInfo(name)
                            || name.contains("..")) {
                        continue;
                    }
                    byte[] bytes = in.readAllBytes();
                    if (name.startsWith("META-INF/services/")) {
                        services.computeIfAbsent(name, key -> new LinkedHashSet<>())
                                .addAll(lines(bytes));
                        continue;  // written merged, after the walk
                    }
                    if (written.add(name)) {
                        writeEntry(out, name, bytes);
                    }  // a duplicate resource: first wins
                }
            }
        }
        for (Map.Entry<String, Set<String>> service : services.entrySet()) {
            writeEntry(out, service.getKey(), String.join("\n", service.getValue())
                    .getBytes());
        }
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

    private static List<String> lines(byte[] bytes) {
        List<String> lines = new ArrayList<>();
        for (String line : new String(bytes).split("\r?\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] bytes)
            throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
