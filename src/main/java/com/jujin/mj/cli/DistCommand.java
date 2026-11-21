package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.project.MjProject;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.LockFileReader;
import com.jujin.mj.store.LockedArtifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code mj dist}: assembles the full distribution directory — the release
 * artifact that users download and install. Distinct from {@code mj package}
 * (which produces a single uber-jar): the dist directory carries a launcher,
 * separate jars, setup.sh, and a tar.gz — readable, inspectable, updatable
 * jar-by-jar.
 *
 * <pre>
 *   target/{name}-{version}/
 *   ├── bin/{name}             POSIX launcher (main class from mj.toml)
 *   ├── lib/
 *   │   ├── {name}-{version}.jar  the project jar
 *   │   ├── &lt;closure jars&gt;        sha-verified dependencies
 *   │   └── toolchain/            lifecycle toolchain (if any)
 *   ├── setup.sh               self-installing script
 *   └── SHA256SUMS             integrity manifest
 * </pre>
 *
 * <p>Plus a tar.gz of the whole directory — the release artifact uploaded to
 * GitHub/Gitee Releases for {@code curl | bash} one-liner installation.
 */
public final class DistCommand implements Command {

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
        # setup.sh — installs this distribution to $HOME/.%1$s/
        # Surgical update: replaces the runtime (lib/bin), leaves the
        # content-addressed store ($HOME/.%1$s/store) untouched.

        set -e

        JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed 's/.*"\\([0-9]*\\)\\..*/\\1/')
        if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 25 ]; then
            echo "%1$s requires JDK 25+ (found: ${JAVA_MAJOR:-none})"
            echo "  download: https://adoptium.net/temurin/releases/?version=25"
            exit 1
        fi

        SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
        INSTALL_DIR="$HOME/.%1$s"
        BIN_DIR="$HOME/.%1$s/bin"

        echo "installing %1$s %2$s to $INSTALL_DIR ..."
        rm -rf "$INSTALL_DIR/lib" "$INSTALL_DIR/bin" "$INSTALL_DIR/SHA256SUMS"
        mkdir -p "$BIN_DIR" "$INSTALL_DIR"
        cp -R "$SCRIPT_DIR"/lib "$INSTALL_DIR/"
        cp "$SCRIPT_DIR"/SHA256SUMS "$INSTALL_DIR/" 2>/dev/null || true

        cat > "$BIN_DIR/%1$s" << 'LAUNCHER_EOF'
        %3$s
        LAUNCHER_EOF
        chmod +x "$BIN_DIR/%1$s"

        USER_SHELL=$(basename "${SHELL:-/bin/bash}")
        case "$USER_SHELL" in
            zsh)  CONFIG_FILE="$HOME/.zshrc" ;;
            *)    CONFIG_FILE="$HOME/.bashrc" ;;
        esac
        if [ -f "$CONFIG_FILE" ] && grep -qF '.%1$s/bin' "$CONFIG_FILE" 2>/dev/null; then
            true
        else
            echo 'export PATH="$PATH:$HOME/.%1$s/bin"' >> "$CONFIG_FILE"
            echo "  PATH updated in $CONFIG_FILE"
        fi

        echo "%1$s %2$s installed — run '%1$s' to verify"
        """;

    private final ArtifactStore store;
    private final Path projectDir;

    @Inject
    public DistCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "dist";
    }

    @Override
    public String summary() {
        return "assemble the distribution directory (bin + lib + setup.sh + tar.gz)";
    }

    @Override
    public int execute(List<String> args) {
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

        if (!Files.isDirectory(projectDir.resolve(CLASSES))) {
            System.err.println("mj: " + CLASSES + " is missing — run mj build first");
            return 1;
        }

        String name = project.name();
        String version = project.version();
        Path dist = projectDir.resolve("target/" + name + "-" + version);
        Path lib = dist.resolve("lib");
        Path bin = dist.resolve("bin");

        // rerun-safe: assembled launchers are read-only — remove the previous dist
        if (Files.isDirectory(dist)) {
            try (Stream<Path> walk = Files.walk(dist)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                throw new IllegalStateException("cannot clean " + path, e);
                            }
                        });
            } catch (IOException e) {
                System.err.println("mj: cannot clean the previous dist: " + e.getMessage());
                return 1;
            }
        }

        try {
            Files.createDirectories(bin);
            Files.createDirectories(lib);

            // the project jar
            Process jarTool = new ProcessBuilder("jar", "--create", "--file",
                    lib.resolve(name + "-" + version + ".jar").toString(),
                    "--no-manifest", "-C", projectDir.resolve(CLASSES).toString(), ".")
                    .inheritIO().start();
            if (jarTool.waitFor() != 0) {
                System.err.println("mj: cannot jar the build output");
                return 1;
            }

            // the runtime closure
            for (LockedArtifact artifact : locked) {
                Path jar = jars.get(artifact.id());
                String fileName = originalName(artifact);
                Files.copy(jar, lib.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            }

            // the launcher — main class from [project].main
            String launcherMain = project.main() != null ? project.main() : "";
            if (launcherMain.isBlank()) {
                launcherMain = "com.jujin.mj.cli.UsageStub";
            }
            Files.writeString(bin.resolve(name),
                    LAUNCHER_TEMPLATE.formatted(launcherMain), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(bin.resolve(name),
                    PosixFilePermissions.fromString("r-xr-xr-x"));

            // setup.sh — self-installing; the embedded launcher is the same
            // launcher the dist ships, spliced verbatim into the heredoc
            String launcherBody = LAUNCHER_TEMPLATE.formatted(launcherMain);
            String setupScript = SETUP_TEMPLATE.formatted(name, version, launcherBody);
            Files.writeString(dist.resolve("setup.sh"), setupScript, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(dist.resolve("setup.sh"),
                    PosixFilePermissions.fromString("r-xr-xr-x"));

            // SHA256SUMS
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

            // tar.gz — the release artifact
            Path tarGz = projectDir.resolve("target/" + name + "-" + version + ".tar.gz");
            Process tar = new ProcessBuilder("tar", "-czf", tarGz.toString(),
                    "-C", dist.getParent().toString(), dist.getFileName().toString())
                    .inheritIO().start();
            tar.waitFor();

            System.out.println("dist " + dist);
            System.out.println("  " + bin.resolve(name) + " — the launcher");
            System.out.println("  " + dist.resolve("setup.sh") + " — self-installing");
            System.out.println("  " + tarGz + " — the release artifact");
            return 0;
        } catch (IOException | InterruptedException e) {
            System.err.println("mj: dist assembly failed: " + e.getMessage());
            return 1;
        }
    }

    private static String originalName(LockedArtifact artifact) {
        String url = artifact.url();
        String name = url.substring(url.lastIndexOf('/') + 1);
        return name.isBlank() || !name.contains(".")
                ? artifact.sha256().substring(0, 12) + ".jar"
                : name;
    }
}
