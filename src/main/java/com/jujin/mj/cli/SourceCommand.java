package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.resolve.ArtifactLocations;
import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.LockFileReader;
import com.jujin.mj.store.LockedArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code mj source <id>} — fetch a dependency's source tree, addressed by the
 * tag the lockfile already pins.
 *
 * <p>mj does not publish {@code -sources.jar}: Go and Cargo never split
 * source from artifact, and a source jar is a patch for Java's binary-first
 * distribution, not a capability. What matters is that source is
 * <em>addressable</em> — and the lockfile already carries the exact tag.
 *
 * <pre>
 *   gh:/gitee:  host/owner/repo/archive/refs/tags/{tag}.tar.gz  → unpacked
 *   central:    the Maven sources jar, when the publisher shipped one
 *   url:        no source location exists — refused with the reason
 * </pre>
 *
 * <p>Extracted trees live under {@code ~/.mj/sources/{id}@{version}/} so an
 * IDE can attach the same path repeatedly.
 */
public final class SourceCommand implements Command {

    private static final Pattern RELEASE_DOWNLOAD = Pattern.compile(
            "https?://[^/]+/([^/]+)/([^/]+)/releases/download/([^/]+)/.*");

    private final ArtifactStore store;
    private final Path projectDir;

    @Inject
    public SourceCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "source";
    }

    @Override
    public String summary() {
        return "fetch a dependency's source tree by its locked tag: mj source <id>";
    }

    @Override
    public int execute(List<String> args) {
        if (args.size() != 1) {
            System.err.println("mj: mj source needs one dependency id — mj source \"group:artifact\"");
            return 2;
        }
        String id = args.getFirst();

        List<LockedArtifact> locked;
        try {
            locked = LockFileReader.read(Files.readString(projectDir.resolve("mj.lock")));
        } catch (IOException e) {
            System.err.println("mj: no readable mj.lock in " + projectDir + " — run mj lock first");
            return 1;
        }

        LockedArtifact artifact = locked.stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElse(null);
        if (artifact == null) {
            System.err.println("mj: '" + id + "' is not in the closure — mj why " + id
                    + " shows what is");
            return 1;
        }

        Path target = sourcesDir().resolve(id.replace(':', '_') + "@" + artifact.version());

        if (artifact.source().startsWith("gh:") || artifact.source().startsWith("gitee:")) {
            return fetchNativeTree(artifact, target);
        }
        if (artifact.source().equals("url")) {
            System.err.println("mj: '" + id + "' is a bare URL leaf — a jar URL carries no "
                    + "tag, so there is no source tree to address");
            return 1;
        }
        return fetchCentralSources(artifact, target);
    }

    // ------------------------------------------------------------------
    // mj-native: the archive of the pinned tag
    // ------------------------------------------------------------------

    private int fetchNativeTree(LockedArtifact artifact, Path target) {
        Matcher matcher = RELEASE_DOWNLOAD.matcher(artifact.url());
        if (!matcher.matches()) {
            System.err.println("mj: cannot read the release coordinates out of " + artifact.url());
            return 1;
        }
        String host = artifact.url().substring(0, artifact.url().indexOf('/', 8));
        String owner = matcher.group(1);
        String repo = matcher.group(2);
        String tag = matcher.group(3);
        String archive = host + "/" + owner + "/" + repo + "/archive/refs/tags/" + tag + ".tar.gz";

        Path tarGz;
        try {
            tarGz = store.ensure(archive, ArtifactStore.Kind.ARCHIVE);
        } catch (RuntimeException e) {
            System.err.println("mj: no source archive for " + owner + "/" + repo + "@" + tag
                    + " — " + e.getMessage());
            System.err.println("  some hosts do not serve tag archives; try "
                    + "git clone --depth 1 --branch " + tag
                    + " " + host.replace("https://", "https://").replace("http://", "http://")
                    + "/" + owner + "/" + repo + ".git");
            return 1;
        }

        if (!unpack(tarGz, target)) {
            return 1;
        }
        report(artifact, target, host + "/" + owner + "/" + repo + "/tree/" + tag);
        return 0;
    }

    // ------------------------------------------------------------------
    // Central: the publisher's own sources jar, when one exists
    // ------------------------------------------------------------------

    private int fetchCentralSources(LockedArtifact artifact, Path target) {
        int colon = artifact.id().indexOf(':');
        if (colon < 0) {
            System.err.println("mj: '" + artifact.id() + "' is not a group:artifact coordinate");
            return 1;
        }
        String group = artifact.id().substring(0, colon);
        String name = artifact.id().substring(colon + 1);
        String baseUrl = artifact.source().equals("central")
                ? ArtifactLocations.centralBase()
                : artifact.source();
        // Maven's classifier layout: lib-1.0.0-sources.jar, not lib-1.0.0.sources.jar
        String root = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String url = root + group.replace('.', '/') + "/" + name + "/" + artifact.version()
                + "/" + name + "-" + artifact.version() + "-sources.jar";
        String base = baseUrl;

        Path jar;
        try {
            jar = store.ensure(url, ArtifactStore.Kind.JAR);
        } catch (RuntimeException e) {
            System.err.println("mj: " + artifact.id() + "@" + artifact.version()
                    + " published no sources jar on " + base);
            System.err.println("  this publisher shipped binaries only — read the bytecode "
                    + "with your IDE's decompiler, or ask the publisher for source access");
            return 1;
        }

        if (!unpackJar(jar, target)) {
            return 1;
        }
        report(artifact, target, null);
        return 0;
    }

    // ------------------------------------------------------------------
    // extraction
    // ------------------------------------------------------------------

    private boolean unpack(Path tarGz, Path target) {
        try {
            Files.createDirectories(target);
            Process tar = new ProcessBuilder("tar", "-xzf", tarGz.toString(),
                    "-C", target.toString(), "--strip-components=1")
                    .inheritIO().start();
            if (tar.waitFor() != 0) {
                System.err.println("mj: cannot unpack " + tarGz);
                return false;
            }
            return true;
        } catch (IOException e) {
            System.err.println("mj: cannot run tar — is it on the PATH? " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean unpackJar(Path jar, Path target) {
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            Files.createDirectories(target);
            for (var entry : zip.stream().toList()) {
                Path destination = target.resolve(entry.getName()).normalize();
                if (!destination.startsWith(target)) {
                    System.err.println("mj: the sources jar contains a path escape: "
                            + entry.getName());
                    return false;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, destination);
                }
            }
            return true;
        } catch (IOException e) {
            System.err.println("mj: cannot read " + jar + ": " + e.getMessage());
            return false;
        }
    }

    private void report(LockedArtifact artifact, Path target, String browserUrl) {
        System.out.println("source " + artifact.id() + "@" + artifact.version() + " → " + target);
        if (browserUrl != null) {
            System.out.println("  browse: " + browserUrl);
        }
        System.out.println("  attach " + target + " as a source root in your IDE");
    }

    /** {@code ~/.mj/sources} — outside the store: extracted trees, not pinned bytes. */
    private static Path sourcesDir() {
        String override = System.getProperty("mj.sources.root");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".mj", "sources");
    }
}
