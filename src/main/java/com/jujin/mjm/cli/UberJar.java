package com.jujin.mjm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The uber-jar writer shared by {@code mjm package --uber} and {@code mjm dist}:
 * project classes first, then every closure jar merged in, with
 * {@code META-INF/services} files concatenated line-wise (ServiceLoader
 * survives), signatures and {@code module-info} dropped, and the first writer
 * winning on any duplicate resource.
 *
 * <p>mjm's own dist ships as one {@code lib/mjm-<version>.jar}: the framework it
 * is built on (freeway, slf4j) is its skeleton, not a dependency a user could
 * swap. The lifecycle toolchain (junit) is deliberately *not* merged — it is
 * only needed by {@code mjm test}, and lives in {@code lib/toolchain/}.
 */
final class UberJar {

    private UberJar() {}

    /**
     * What to merge: the project's classes, any local workspace module's classes
     * (its jar never existed), then the jars, in merge order.
     */
    record Input(Path classes, List<Path> jars, List<Path> classDirs,
                 List<String> dependencyLabels) {

        Input {
            jars = List.copyOf(jars);
            classDirs = List.copyOf(classDirs);
            dependencyLabels = List.copyOf(dependencyLabels);
        }

        Input(Path classes, List<Path> jars, List<String> dependencyLabels) {
            this(classes, jars, List.of(), dependencyLabels);
        }
    }

    /** Header facts for the generated manifest. */
    record Header(String title, String version, String mainClass) {}

    /**
     * Writes the uber-jar. Throws on I/O failure — callers turn that into
     * their own exit codes and messages.
     */
    static void write(Path output, Input input, Header header) throws IOException {
        Files.createDirectories(output.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
            Manifest manifest = new Manifest();
            Attributes attributes = manifest.getMainAttributes();
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            if (header.mainClass() != null && !header.mainClass().isBlank()) {
                attributes.put(Attributes.Name.MAIN_CLASS, header.mainClass());
            }
            attributes.put(new Attributes.Name("Built-By"), "mjm");
            if (header.title() != null && !header.title().isBlank()) {
                attributes.put(new Attributes.Name("Implementation-Title"), header.title());
            }
            if (header.version() != null && !header.version().isBlank()) {
                attributes.put(new Attributes.Name("Implementation-Version"), header.version());
                attributes.put(new Attributes.Name("mjm-Version"), header.version());
            }
            if (!input.dependencyLabels().isEmpty()) {
                attributes.put(new Attributes.Name("mjm-Dependencies"),
                        String.join(" ", input.dependencyLabels()));
            }
            // A multi-release jar only gets its META-INF/versions/* classes
            // honored when the manifest says so. The flag is set when any
            // merged jar carried versioned entries; without it those classes
            // are dead weight, and with it set for a jar that has none the JVM
            // just finds nothing to override — both harmless, but only one is
            // correct for a jar that does carry them.
            if (carriesVersionedEntries(input.jars(), input.classDirs())) {
                attributes.put(new Attributes.Name("Multi-Release"), "true");
            }
            out.putNextEntry(ZipEntries.entry("META-INF/MANIFEST.MF"));
            manifest.write(out);
            out.closeEntry();

            // the project's own classes win every collision
            Set<String> written = new LinkedHashSet<>();
            try (Stream<Path> stream = Files.walk(input.classes())) {
                List<Path> files = stream.filter(Files::isRegularFile)
                        .sorted(java.util.Comparator.comparing(
                                file -> input.classes().relativize(file).toString()))
                        .toList();
                for (Path file : files) {
                    String entry = input.classes().relativize(file).toString().replace('\\', '/');
                    if (written.add(entry)) {
                        writeEntry(out, entry, Files.readAllBytes(file));
                    }
                }
            }

            Map<String, Set<String>> services = new TreeMap<>();
            for (Path classDir : input.classDirs()) {
                try (Stream<Path> stream = Files.walk(classDir)) {
                    List<Path> files = stream.filter(Files::isRegularFile)
                            .sorted(java.util.Comparator.comparing(
                                    file -> classDir.relativize(file).toString()))
                            .toList();
                    for (Path file : files) {
                        String name = classDir.relativize(file).toString().replace('\\', '/');
                        if (name.equals("META-INF/MANIFEST.MF") || isSignature(name)
                                || isModuleInfo(name)) {
                            continue;
                        }
                        byte[] bytes = Files.readAllBytes(file);
                        if (name.startsWith("META-INF/services/")) {
                            services.computeIfAbsent(name, key -> new LinkedHashSet<>())
                                    .addAll(serviceLines(bytes));
                            continue;
                        }
                        if (written.add(name)) {
                            writeEntry(out, name, bytes);
                        }
                    }
                }
            }
            for (Path jar : input.jars()) {
                try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
                    JarEntry entry;
                    while ((entry = in.getNextJarEntry()) != null) {
                        String name = entry.getName();
                        if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF")
                                || isSignature(name) || isModuleInfo(name)) {
                            continue;
                        }
                        byte[] bytes = in.readAllBytes();
                        if (name.startsWith("META-INF/services/")) {
                            services.computeIfAbsent(name, key -> new LinkedHashSet<>())
                                    .addAll(serviceLines(bytes));
                            continue;
                        }
                        if (written.add(name)) {
                            writeEntry(out, name, bytes);
                        }
                    }
                }
            }
            for (Map.Entry<String, Set<String>> service : services.entrySet()) {
                writeEntry(out, service.getKey(),
                        String.join("\n", service.getValue()).getBytes());
            }
        }
    }

    /** Whether any of these sources carries {@code META-INF/versions/} entries. */
    private static boolean carriesVersionedEntries(List<Path> jars, List<Path> classDirs)
            throws IOException {
        for (Path dir : classDirs) {
            try (Stream<Path> stream = Files.walk(dir)) {
                if (stream.anyMatch(file -> file.toString().replace('\\', '/')
                        .contains("/META-INF/versions/"))) {
                    return true;
                }
            }
        }
        for (Path jar : jars) {
            try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
                JarEntry entry;
                while ((entry = in.getNextJarEntry()) != null) {
                    if (entry.getName().startsWith("META-INF/versions/")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] bytes)
            throws IOException {
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

    /** A service file's provider class names, comments and blanks dropped. */
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

    /** Ordered labels for the manifest, skipping dev-scoped rows. */
    static List<String> labels(List<com.jujin.mjm.store.LockedArtifact> locked) {
        List<String> labels = new ArrayList<>();
        for (var artifact : locked) {
            if (artifact.isMain()) {
                labels.add(artifact.id() + "@" + artifact.version());
            }
        }
        return labels;
    }

}
