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
 * {@code jeno bundle} — assembles an installable distribution directory. This is
 * <b>not</b> a general "package my library" step; that is {@code jeno package},
 * and its single product is a jar.
 *
 * <p>bundle exists for one job: producing the artifact an application ships to
 * its own users — the thing behind a {@code curl | bash}. jeno itself is the first
 * consumer; any CLI project can use it. A library never needs it.
 *
 * <pre>
 *   target/dist/{name}-{version}/
 *   ├── bin/{name}                POSIX launcher (main class from jeno.toml)
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
 * CI. jeno provides the packaging mechanics (merge the skeleton, place the
 * launcher, write setup.sh, checksum it) and nothing else.
 */
public final class BundleCommand implements Command {

    private static final String CLASSES = "target/classes";

    private static final String LAUNCHER_TEMPLATE = """
        #!/bin/sh
        # %1$s %2$s launcher

        PRG="$0"
        while [ -h "$PRG" ]; do
            link=$(ls -ld "$PRG" | sed -e 's/^.* -> //')
            if [ -n "$link" ]; then
                case "$link" in /*) PRG="$link" ;; *) PRG="$(dirname "$PRG")/$link" ;; esac
            fi
            PRG=$(dirname "$PRG")
        done
        JENO_HOME=$(cd "$(dirname "$PRG")/.." && pwd)

        if [ -n "$JAVA_HOME" ]; then
            JAVA="$JAVA_HOME/bin/java"
        else
            JAVA=$(command -v java 2>/dev/null)
        fi
        if [ -z "$JAVA" ]; then
            echo "%1$s: no java found — set JAVA_HOME or add java to PATH" >&2
            exit 1
        fi

        # lib/ holds this program with its shipped dependencies merged. The
        # lifecycle toolchain under lib/toolchain/ is test-only and is not on
        # this classpath.
        exec "$JAVA" -cp "$JENO_HOME/lib/*" %3$s "$@"
        """;

    private static final String SETUP_TEMPLATE = """
        #!/bin/bash
        # setup.sh — installs %1$s %2$s to $HOME/.%1$s/
        # Verifies SHA256SUMS, then swaps lib/bin through a staging directory.
        # The content-addressed store and any other files under
        # $HOME/.%1$s are left alone.

        set -e

        JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed 's/.*"\\([0-9]*\\)\\..*/\\1/')
        if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt %4$d ]; then
            echo "%1$s requires JDK %4$d+ (found: ${JAVA_MAJOR:-none})"
            echo "  install a JDK %4$d+ and put it on PATH, or set JAVA_HOME"
            exit 1
        fi

        SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
        INSTALL_DIR="$HOME/.%1$s"
        BIN_DIR="$INSTALL_DIR/bin"

        if command -v sha256sum >/dev/null 2>&1; then
            CHECK="sha256sum -c"
        elif command -v shasum >/dev/null 2>&1; then
            CHECK="shasum -a 256 -c"
        else
            echo "%1$s: need sha256sum or shasum to verify the distribution" >&2
            exit 1
        fi
        if ! ( cd "$SCRIPT_DIR" && $CHECK SHA256SUMS >/dev/null ); then
            echo "%1$s: SHA256SUMS verification failed — the download is corrupt" >&2
            exit 1
        fi

        echo "installing %1$s %2$s to $INSTALL_DIR ..."
        mkdir -p "$INSTALL_DIR" "$BIN_DIR"
        STAGING=$(mktemp -d "$INSTALL_DIR/.staging.XXXXXX")
        BACKUP=$(mktemp -d "$INSTALL_DIR/.backup.XXXXXX")
        trap 'rm -rf "$STAGING" "$BACKUP"' EXIT

        cp -R "$SCRIPT_DIR/lib" "$STAGING/lib"
        cp "$SCRIPT_DIR/SHA256SUMS" "$STAGING/SHA256SUMS"

        if [ -d "$INSTALL_DIR/lib" ]; then mv "$INSTALL_DIR/lib" "$BACKUP/lib"; fi
        if [ -d "$INSTALL_DIR/bin" ]; then mv "$INSTALL_DIR/bin" "$BACKUP/bin"; fi
        cp "$SCRIPT_DIR/uninstall.sh" "$STAGING/uninstall.sh"
        if ! mv "$STAGING/lib" "$INSTALL_DIR/lib" \\
                || ! mv "$STAGING/SHA256SUMS" "$INSTALL_DIR/SHA256SUMS" \\
                || ! mv "$STAGING/uninstall.sh" "$INSTALL_DIR/uninstall.sh"; then
            rm -rf "$INSTALL_DIR/lib"
            rm -f "$INSTALL_DIR/uninstall.sh"
            if [ -d "$BACKUP/lib" ]; then mv "$BACKUP/lib" "$INSTALL_DIR/lib"; fi
            if [ -d "$BACKUP/bin" ]; then mv "$BACKUP/bin" "$INSTALL_DIR/bin"; fi
            echo "%1$s: install failed — the previous version was restored" >&2
            exit 1
        fi
        rm -rf "$BACKUP"

        mkdir -p "$BIN_DIR"
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

    private static final String UNINSTALL_TEMPLATE = """
        #!/bin/sh
        # removes $HOME/.%1$s; the PATH line in your shell rc is yours to remove
        set -e
        rm -rf "$HOME/.%1$s"
        echo "removed $HOME/.%1$s"
        echo "remove the .%1$s/bin PATH line from your shell rc by hand"
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
        return "assemble an installable distribution for apps that ship to users";
    }

    @Override
    public String usage() {
        return "[--toolchain]";
    }

    @Override
    public String help() {
        return """
               --toolchain also ships the artifacts named in [toolchain].jars under
               lib/toolchain/; they never merge into the launcher jar.""";
    }

    @Override
    public Command.Category category() {
        return Command.Category.BUILD;
    }

    @Override
    public int order() {
        return 50;
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
        if (ProjectFiles.rejectLegacy(projectDir)) {
            return 1;
        }
        boolean json = args.contains("--json");
        List<String> cleaned = args.stream().filter(arg -> !arg.equals("--json")).toList();
        if (json) {
            return JsonWorkspace.run("bundle", projectDir, cleaned, this::jsonModule);
        }
        return executeHuman(cleaned);
    }

    private JsonWorkspace.ModuleResult jsonModule(Path moduleDir, List<String> args) {
        long started = System.nanoTime();
        JsonCapture.Result captured = JsonCapture.run(() ->
                new BundleCommand(store, moduleDir).executeHuman(args));
        if (captured.exitCode() != 0) {
            return JsonWorkspace.ModuleResult.failed(captured.exitCode(),
                    captured.exitCode() == 2 ? "USAGE" : "BUNDLE_FAILED", captured.message());
        }
        try {
            JenoProject project = ProjectReader.parse(
                    Files.readString(moduleDir.resolve("jeno.toml")));
            Path dist = moduleDir.resolve("target/dist");
            Path bundleDir = dist.resolve(project.name() + "-" + project.version())
                    .toAbsolutePath().normalize();
            Path tar = dist.resolve(project.name() + "-" + project.version() + ".tar.gz")
                    .toAbsolutePath().normalize();
            if (!Files.isDirectory(bundleDir) || !Files.isRegularFile(tar)) {
                return JsonWorkspace.ModuleResult.failed(1, "ARTIFACT_MISSING",
                        "bundle reported success but did not write " + bundleDir + " and " + tar);
            }
            JsonObject data = JsonUtils.object();
            data.put("project", project.name() + "@" + project.version());
            data.put("toolchain", args.contains("--toolchain"));
            data.put("durationMs", (System.nanoTime() - started) / 1_000_000L);

            JsonObject files = JsonUtils.object();
            files.put("launcher", bundleDir.resolve("bin")
                    .resolve(project.name()).toString());
            files.put("setupScript", bundleDir.resolve("setup.sh").toString());
            files.put("uninstallScript", bundleDir.resolve("uninstall.sh").toString());
            files.put("checksums", bundleDir.resolve("SHA256SUMS").toString());
            data.put("files", files);

            JsonArray artifacts = JsonUtils.array();
            JsonObject directory = JsonUtils.object();
            directory.put("kind", "bundle-directory");
            directory.put("path", bundleDir.toString());
            directory.put("fileCount", countFiles(bundleDir));
            directory.put("bytes", treeSize(bundleDir));
            artifacts.add(directory);
            JsonObject archive = JsonUtils.object();
            archive.put("kind", "tar.gz");
            archive.put("path", tar.toString());
            archive.put("sha256", ArtifactStore.sha256(tar));
            archive.put("bytes", Files.size(tar));
            artifacts.add(archive);
            data.put("artifacts", artifacts);
            return JsonWorkspace.ModuleResult.ok(data);
        } catch (IOException | RuntimeException e) {
            return JsonWorkspace.ModuleResult.failed(1, "RESULT_UNREADABLE",
                    "bundle finished but its result could not be described: " + e.getMessage());
        }
    }

    private int executeHuman(List<String> args) {
        Integer workspace = WorkspaceRun.orchestrate(projectDir, args, "bundled", false,
                (dir, rest) -> new BundleCommand(store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean withToolchain = false;
        for (String arg : args) {
            if (arg.equals("--toolchain")) {
                withToolchain = true;
            } else {
                System.err.println(Ui.errPrefix() + "bundle takes --toolchain only — got '" + arg + "'");
                return 2;
            }
        }
        boolean includeToolchain = withToolchain;
        // the whole assembly writes target/dist/: jar, launcher, setup, archive
        return ProjectLock.with(projectDir, () -> assemble(includeToolchain));
    }

    private int assemble(boolean withToolchain) {
        DistOutput.warnLegacy(projectDir);
        JenoProject project;
        List<LockedArtifact> locked;
        Map<String, Path> jars;
        Substitutions substitutions;
        try {
            project = ProjectReader.parse(
                    Files.readString(projectDir.resolve("jeno.toml")));
            locked = LockFileReader.read(
                    Files.readString(projectDir.resolve("jeno.lock")));
            substitutions = Substitutions.forProject(projectDir);
            jars = store.verifiedJars(locked,
                    artifact -> substitutions.find(artifact.id()) != null);
            java.util.Set<String> lockedIds = new java.util.HashSet<>();
            for (LockedArtifact artifact : locked) {
                lockedIds.add(artifact.id());
            }
            for (String id : project.toolchain()) {
                if (!lockedIds.contains(id)) {
                    System.err.println(Ui.errPrefix() + "[toolchain].jars names '" + id
                            + "' but it is not in jeno.lock — run jeno lock");
                    return 1;
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }

        if (!Files.isDirectory(projectDir.resolve(CLASSES))) {
            System.err.println(Ui.errPrefix() + CLASSES + " is missing — run jeno build first");
            return 1;
        }
        if (project.main() == null || project.main().isBlank()) {
            System.err.println(Ui.errPrefix() + "bundle needs [project].main — set it in jeno.toml");
            return 1;
        }

        String name = project.name();
        String version = project.version();
        Path dist;
        try {
            dist = DistOutput.dist(projectDir).resolve(name + "-" + version);
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot prepare target/dist/ — " + e.getMessage());
            return 1;
        }
        Path lib = dist.resolve("lib");
        Path bin = dist.resolve("bin");

        // rerun-safe: the launcher is read-only and the archive is stale-able —
        // both are this version's outputs, so both are cleared first
        try {
            DistOutput.clear(dist);
            DistOutput.clear(dist.resolveSibling(name + "-" + version + ".tar.gz"));
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot clean the previous output: " + e.getMessage());
            return 1;
        }

        try {
            Files.createDirectories(bin);
            Files.createDirectories(lib);

            // The project as ONE jar: its own classes plus the main runtime
            // closure. Artifacts named in [toolchain].jars are different: they
            // are build/test machinery, never part of the running program, so
            // they are omitted unless --toolchain asks for them and then ship
            // under lib/toolchain/. This is an explicit manifest fact, not a
            // guess based on package names.
            // A workspace module contributes its classes wherever its jar would
            // have gone, so the dist carries the local code, not the locked copy.
            List<Path> mergeJars = new ArrayList<>();
            List<Path> mergeDirs = new ArrayList<>();
            List<LockedArtifact> mergedRows = new ArrayList<>();
            List<LockedArtifact> toolchainRows = new ArrayList<>();
            java.util.Set<String> declaredToolchain = java.util.Set.copyOf(project.toolchain());
            for (LockedArtifact artifact : locked) {
                // [toolchain] wins over scope: a PROCESSOR artifact can be part
                // of the test toolchain even though it never merges at runtime.
                if (declaredToolchain.contains(artifact.id())) {
                    if (withToolchain) {
                        toolchainRows.add(artifact);
                    }
                    continue;
                }
                if (artifact.scope() == com.jujin.jeno.resolve.Scope.PROCESSOR) {
                    continue;
                }
                if (!artifact.isMain()) {
                    continue;
                }
                Substitutions.Module local = substitutions.find(artifact.id());
                if (local != null) {
                    mergeDirs.add(substitutions.localClasses(local, artifact.version()));
                    mergedRows.add(artifact);
                    substitutions.noteEmbedded(local);
                } else {
                    Path jar = jars.get(artifact.id());
                    if (jar != null) {
                        mergeJars.add(jar);
                        mergedRows.add(artifact);
                    }
                }
            }
            List<String> mergedLabels = new ArrayList<>();
            for (LockedArtifact artifact : mergedRows) {
                mergedLabels.add(artifact.id() + "@" + artifact.version());
            }
            try {
                UberJar.write(lib.resolve(name + "-" + version + ".jar"),
                        new UberJar.Input(projectDir.resolve(CLASSES), mergeJars,
                                mergeDirs, List.copyOf(mergedLabels)),
                        new UberJar.Header(name, version, project.main()));
            } catch (IOException e) {
                System.err.println(Ui.errPrefix() + "cannot assemble " + name + ".jar: " + e.getMessage());
                return 1;
            }

            // the lifecycle toolchain — shipped apart, loaded only by jeno test
            if (!toolchainRows.isEmpty()) {
                Path toolchainDir = lib.resolve("toolchain");
                Files.createDirectories(toolchainDir);
                for (LockedArtifact artifact : toolchainRows) {
                    Path destination = toolchainDir.resolve(originalName(artifact));
                    Substitutions.Module local = substitutions.find(artifact.id());
                    if (local != null) {
                        substitutions.localClasses(local, artifact.version());
                        substitutions.noteEmbedded(local);
                        if (jar(local.classes(), destination, local.id(), local.version(),
                                null) != 0) {
                            return 1;
                        }
                        continue;
                    }
                    Path jar = jars.get(artifact.id());
                    if (jar != null) {
                        Files.copy(jar, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            // the launcher — [project].main is required, so there is no fallback
            String launcherMain = project.main();
            String launcherBody = LAUNCHER_TEMPLATE.formatted(name, version, launcherMain);
            Files.writeString(bin.resolve(name), launcherBody, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(bin.resolve(name),
                    PosixFilePermissions.fromString("r-xr-xr-x"));

            // A runnable module has a target major even without [java].version:
            // without --release, javac emits the running JDK's bytecode.
            int jdkMajor = project.javaVersion() != null
                    ? project.javaVersion() : Runtime.version().feature();

            // setup.sh — verifies, stages, swaps; the embedded launcher is the
            // same launcher the dist ships, spliced verbatim into the heredoc
            String setupScript = SETUP_TEMPLATE.formatted(name, version, launcherBody, jdkMajor);
            Files.writeString(dist.resolve("setup.sh"), setupScript, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(dist.resolve("setup.sh"),
                    PosixFilePermissions.fromString("r-xr-xr-x"));

            Files.writeString(dist.resolve("uninstall.sh"),
                    UNINSTALL_TEMPLATE.formatted(name), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(dist.resolve("uninstall.sh"),
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
            Path tarGz = dist.resolveSibling(name + "-" + version + ".tar.gz");
            Process tar = new ProcessBuilder("tar", "-czf", tarGz.toString(),
                    "-C", dist.getParent().toString(), dist.getFileName().toString())
                    .inheritIO().start();
            tar.waitFor();

            System.out.println(Ui.ok("dist " + dist));
            System.out.println("  " + Ui.dim(bin.resolve(name) + " — the launcher"));
            System.out.println("  " + Ui.dim(dist.resolve("setup.sh") + " — self-installing"));
            System.out.println("  " + Ui.dim(tarGz + " — the release artifact"));
            return 0;
        } catch (IOException | InterruptedException e) {
            System.err.println(Ui.errPrefix() + "dist assembly failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Zips {@code classes} into {@code jarFile} with the JDK's jar tool,
     * adding the Java-conventional manifest entries ({@code
     * Implementation-Title}/{@code Implementation-Version}) so outside tools
     * and IDEs can read the version without jeno's resource.
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
                attributes.put(new java.util.jar.Attributes.Name("jeno-Version"), version);
            }
            if (mainClass != null && !mainClass.isBlank()) {
                attributes.put(java.util.jar.Attributes.Name.MAIN_CLASS, mainClass);
            }
            attributes.put(new java.util.jar.Attributes.Name("Built-By"), "jeno");

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
            System.err.println(Ui.errPrefix() + "cannot run jar — is a JDK on the PATH?");
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
            System.err.println(Ui.errPrefix() + "cannot run jar — is a JDK on the PATH?");
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
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
    private static long countFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
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

}
