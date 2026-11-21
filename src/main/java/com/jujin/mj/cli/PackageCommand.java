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
import java.nio.file.attribute.PosixFilePermissions;
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

    private static final String LAUNCHER_TEMPLATE = """
        #!/bin/sh
        # mj — modern java manager

        PRG="$0"
        while [ -h "$PRG" ]; do
            link=$(ls -ld "$PRG" | sed -e 's/^.* -> //')
            if [ -n "$link" ]; then
                case "$link" in /*) PRG="$link" ;; *) PRG="$(dirname "$PRG")/$link" ;; esac
            fi
            PRG=$(dirname "$PRG")
        done
        MJ_HOME=$(cd "$(dirname "$PRG")/.." && pwd)

        if [ -n "$JAVA_HOME" ]; then
            JAVA="$JAVA_HOME/bin/java"
        else
            JAVA=$(command -v java 2>/dev/null)
        fi
        if [ -z "$JAVA" ]; then
            echo "mj: no java found — set JAVA_HOME or add java to PATH" >&2
            exit 1
        fi

        TOOLCHAIN_CP=""
        if [ -d "$MJ_HOME/lib/toolchain" ]; then
            for jar_dir in "$MJ_HOME/lib/toolchain"/*/; do
                for jar_file in "$jar_dir"*.jar; do
                    if [ -f "$jar_file" ]; then
                        TOOLCHAIN_CP="$TOOLCHAIN_CP:$jar_file"
                    fi
                done
            done
        fi

        exec "$JAVA" -cp "$MJ_HOME/lib/*:$TOOLCHAIN_CP" %s "$@"
        """;

    private static final String SETUP_TEMPLATE = """
        #!/bin/bash
        # setup.sh — installs this distribution to $HOME/.%s/

        set -e

        JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed 's/.*"\\([0-9]*\\)\\..*/\\1/')
        if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 25 ]; then
            echo "mj requires JDK 25+ (found: ${JAVA_MAJOR:-none})"
            echo "  download: https://adoptium.net/temurin/releases/?version=25"
            exit 1
        fi

        SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
        INSTALL_DIR="$HOME/.%s"
        BIN_DIR="$HOME/.%s/bin"

        echo "installing %s %s to $INSTALL_DIR ..."
        rm -rf "$INSTALL_DIR"
        mkdir -p "$BIN_DIR" "$INSTALL_DIR"
        cp -R "$SCRIPT_DIR"/lib "$INSTALL_DIR/"
        cp "$SCRIPT_DIR"/SHA256SUMS "$INSTALL_DIR/" 2>/dev/null || true

        cat > "$BIN_DIR/%s" << 'LAUNCHER_EOF'
        #!/bin/sh
        PRG="$0"
        while [ -h "$PRG" ]; do
            link=$(ls -ld "$PRG" | sed -e 's/^.* -> //')
            if [ -n "$link" ]; then
                case "$link" in /*) PRG="$link" ;; *) PRG="$(dirname "$PRG")/$link" ;; esac
            fi
            PRG=$(dirname "$PRG")
        done
        MJ_HOME=$(cd "$(dirname "$PRG")/.." && pwd)

        if [ -n "$JAVA_HOME" ]; then
            JAVA="$JAVA_HOME/bin/java"
        else
            JAVA=$(command -v java 2>/dev/null)
        fi
        exec "$JAVA" -cp "$MJ_HOME/lib/*" %s "$@"
        LAUNCHER_EOF
        chmod +x "$BIN_DIR/%s"

        USER_SHELL=$(basename "${SHELL:-/bin/bash}")
        case "$USER_SHELL" in
            zsh)  CONFIG_FILE="$HOME/.zshrc" ;;
            *)    CONFIG_FILE="$HOME/.bashrc" ;;
        esac
        if [ -f "$CONFIG_FILE" ] && grep -qF '.%s/bin' "$CONFIG_FILE" 2>/dev/null; then
            true
        else
            echo 'export PATH="$PATH:$HOME/.%s/bin"' >> "$CONFIG_FILE"
            echo "  PATH updated in $CONFIG_FILE"
        fi

        echo "%s %s installed — run '%s' to verify"
        """;

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

        return uber ? uberJar(project, locked, jars, mainClass)
                    : distDir(project, locked, jars, mainClass);
    }

    // ------------------------------------------------------------------
    // dist mode (default)
    // ------------------------------------------------------------------

    private int distDir(MjProject project, List<LockedArtifact> locked,
                        Map<String, Path> jars, String mainClass) {
        String name = project.name();
        String version = project.version();
        Path dist = projectDir.resolve("target/" + name + "-" + version);
        Path lib = dist.resolve("lib");
        Path bin = dist.resolve("bin");

        try {
            Files.createDirectories(bin);
            Files.createDirectories(lib);

            Path classes = projectDir.resolve(CLASSES);
            Process jarTool = new ProcessBuilder("jar", "--create", "--file",
                    lib.resolve(name + "-" + version + ".jar").toString(),
                    "--no-manifest", "-C", classes.toString(), ".")
                    .inheritIO().start();
            if (jarTool.waitFor() != 0) {
                System.err.println("mj: cannot jar the build output");
                return 1;
            }

            for (LockedArtifact artifact : locked) {
                Path jar = jars.get(artifact.id());
                String fileName = originalName(artifact);
                Files.copy(jar, lib.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            }

            String launcherMain = mainClass != null ? mainClass
                    : "com.jujin.mj.cli.UsageStub";
            Files.writeString(bin.resolve(name),
                    LAUNCHER_TEMPLATE.formatted(launcherMain), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(bin.resolve(name),
                    PosixFilePermissions.fromString("r-xr-xr-x"));

            String setupScript = SETUP_TEMPLATE.formatted(
                    name, name, name, name, name, version,
                    name, name, name, name, name, name, name, name);
            Files.writeString(dist.resolve("setup.sh"), setupScript,
                    StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(dist.resolve("setup.sh"),
                    PosixFilePermissions.fromString("r-xr-xr-x"));

            StringBuilder sums = new StringBuilder();
            try (Stream<Path> stream = Files.walk(dist)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(path -> !path.getFileName().toString().equals("SHA256SUMS"))
                        .sorted().toList()) {
                    sums.append(ArtifactStore.sha256(file)).append("  ")
                            .append(dist.relativize(file).toString().replace('\\', '/'))
                            .append('\n');
                }
            }
            Files.writeString(dist.resolve("SHA256SUMS"), sums.toString());

            Path tarGz = projectDir.resolve("target/" + name + "-" + version + ".tar.gz");
            Process tar = new ProcessBuilder("tar", "-czf", tarGz.toString(),
                    "-C", dist.getParent().toString(), dist.getFileName().toString())
                    .inheritIO().start();
            tar.waitFor();

            System.out.println("packaged " + dist);
            System.out.println("  " + bin.resolve(name) + " — the launcher");
            System.out.println("  " + dist.resolve("setup.sh") + " — self-installing");
            System.out.println("  " + tarGz + " — the release artifact");
            return 0;
        } catch (IOException | InterruptedException e) {
            System.err.println("mj: packaging failed: " + e.getMessage());
            return 1;
        }
    }

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
