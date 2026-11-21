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
 * {@code mj bundle} — assembles an installable distribution directory. This is
 * <b>not</b> a general "package my library" step; that is {@code mj package},
 * and its single product is a jar.
 *
 * <p>bundle exists for one job: producing the artifact an application ships to
 * its own users — the thing behind a {@code curl | bash}. mj itself is the first
 * consumer; any CLI project can use it. A library never needs it.
 *
 * <pre>
 *   dist/{name}-{version}/
 *   ├── bin/{name}                POSIX launcher (main class from mj.toml)
 *   ├── lib/
 *   │   ├── {name}-{version}.jar  one jar: project + skeleton merged
 *   │   └── toolchain/            lifecycle toolchain (test-only jars)
 *   ├── setup.sh                  self-installing script
 *   └── SHA256SUMS                integrity manifest
 * </pre>
 *
 * <p>Plus a tar.gz of the whole directory — the asset a release upload carries
 * for one-liner installation.
 *
 * <p><b>What this command does not decide.</b> Whether a project wants a
 * bundle, what to name it, where to upload it — all of that is the project's
 * CI. mj provides the packaging mechanics (merge the skeleton, place the
 * launcher, write setup.sh, checksum it) and nothing else.
 */
public final class BundleCommand implements Command {

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

        # lib/ holds one jar — mj and its skeleton merged. The lifecycle
        # toolchain under lib/toolchain/ is loaded only by the forked test JVM.
        exec "$JAVA" -cp "$MJ_HOME/lib/*" %s "$@"
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
    public BundleCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "bundle";
    }

    @Override
    public String summary() {
        return "assemble an installable distribution (bin + lib + setup.sh + tar.gz) — for apps that ship to users, not for libraries";
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
        Path dist = projectDir.resolve("dist/" + name + "-" + version);
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

            // the project as ONE jar: its own classes plus the runtime closure
            // (freeway, slf4j) merged in. They are mj's skeleton, not swappable
            // dependencies — a user never picks a different freeway-ioc. The
            // lifecycle toolchain (junit) is separate: only `mj test` needs it.
            Map<String, Path> mainJars = UberJar.mainJars(locked, jars);
            List<Path> toolchainJars = toolchainJars(locked, jars);
            // the skeleton alone merges; test-only jars go to lib/toolchain
            List<Path> mergeJars = new ArrayList<>();
            for (Map.Entry<String, Path> entry : mainJars.entrySet()) {
                if (!isTestOnly(entry.getKey())) {
                    mergeJars.add(entry.getValue());
                }
            }
            try {
                UberJar.write(lib.resolve(name + "-" + version + ".jar"),
                        new UberJar.Input(projectDir.resolve(CLASSES), mergeJars,
                                UberJar.labels(locked)),
                        new UberJar.Header(name, version,
                                project.main() == null || project.main().isBlank()
                                        ? null : project.main()));
            } catch (IOException e) {
                System.err.println("mj: cannot assemble " + name + ".jar: " + e.getMessage());
                return 1;
            }

            // the lifecycle toolchain — shipped apart, loaded only by mj test
            if (!toolchainJars.isEmpty()) {
                Path toolchainDir = lib.resolve("toolchain");
                Files.createDirectories(toolchainDir);
                for (LockedArtifact artifact : locked) {
                    if (!isTestOnly(artifact.id())) {
                        continue;
                    }
                    Path jar = jars.get(artifact.id());
                    if (jar != null) {
                        Files.copy(jar, toolchainDir.resolve(originalName(artifact)),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
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
            Path tarGz = projectDir.resolve("dist/" + name + "-" + version + ".tar.gz");
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

    /**
     * Zips {@code classes} into {@code jarFile} with the JDK's jar tool,
     * adding the Java-conventional manifest entries ({@code
     * Implementation-Title}/{@code Implementation-Version}) so outside tools
     * and IDEs can read the version without mj's resource.
     */
    static int jar(Path classes, Path jarFile, String title, String version, String mainClass) {
        try {
            java.util.jar.Manifest manifest = new java.util.jar.Manifest();
            var attributes = manifest.getMainAttributes();
            attributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
            if (title != null && !title.isBlank()) {
                attributes.put(new java.util.jar.Attributes.Name("Implementation-Title"), title);
            }
            if (version != null && !version.isBlank()) {
                attributes.put(new java.util.jar.Attributes.Name("Implementation-Version"),
                        version);
                attributes.put(new java.util.jar.Attributes.Name("mj-Version"), version);
            }
            if (mainClass != null && !mainClass.isBlank()) {
                attributes.put(java.util.jar.Attributes.Name.MAIN_CLASS, mainClass);
            }
            attributes.put(new java.util.jar.Attributes.Name("Built-By"), "mj");

            Path manifestFile = Files.createTempFile("mj-manifest-", ".mf");
            try (var out = Files.newOutputStream(manifestFile)) {
                manifest.write(out);
            }
            List<String> command = List.of("jar", "--create", "--file", jarFile.toString(),
                    "--manifest", manifestFile.toString(), "-C", classes.toString(), ".");
            try {
                Process process = new ProcessBuilder(command).inheritIO().start();
                return process.waitFor();
            } finally {
                Files.deleteIfExists(manifestFile);
            }
        } catch (IOException e) {
            System.err.println("mj: cannot run jar — is a JDK on the PATH?");
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** Zips {@code classes} into {@code jarFile} with no manifest (store-side helpers). */
    static int plainJar(Path classes, Path jarFile) {
        List<String> command = List.of("jar", "--create", "--file", jarFile.toString(),
                "--no-manifest", "-C", classes.toString(), ".");
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            return process.waitFor();
        } catch (IOException e) {
            System.err.println("mj: cannot run jar — is a JDK on the PATH?");
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /**
     * The lifecycle toolchain: the jars {@code mj test} injects when the
     * closure does not already declare them (junit and its platform deps).
     * They ship in {@code lib/toolchain/} instead of the uber-jar because they
     * are not part of the running program — a production run never loads one.
     *
     * <p>Recognized by group: {@code org.junit.*} and {@code org.opentest4j}.
     * Anything else in the closure is skeleton and merges into the jar.
     */
    private static List<Path> toolchainJars(List<LockedArtifact> locked,
                                            Map<String, Path> jars) {
        List<Path> toolchain = new ArrayList<>();
        for (LockedArtifact artifact : locked) {
            if (isTestOnly(artifact.id())) {
                Path jar = jars.get(artifact.id());
                if (jar != null) {
                    toolchain.add(jar);
                }
            }
        }
        return toolchain;
    }

    /** Jars that exist only to run tests — the lifecycle toolchain. */
    private static boolean isTestOnly(String id) {
        return id.startsWith("org.junit.") || id.startsWith("org.opentest4j:");
    }

    /**
     * A readable file name for a locked artifact — {@code artifact-version.jar}
     * derived from the coordinate, not from the URL: store URLs are
     * content-addressed ({@code <sha256>.jar}) and would ship unreadable names.
     */
    private static String originalName(LockedArtifact artifact) {
        String id = artifact.id();
        int colon = id.indexOf(':');
        String artifactPart = colon < 0 ? id : id.substring(colon + 1);
        return artifactPart + "-" + artifact.version() + ".jar";
    }
}
