package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.project.Descriptor;
import com.jujin.mjm.project.DescriptorReader;
import com.jujin.mjm.project.Repository;
import com.jujin.mjm.resolve.ArtifactLocations;
import com.jujin.mjm.store.ArtifactStore;
import com.jujin.mjm.store.LockFileReader;
import com.jujin.mjm.store.LockedArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code mjm info <id>} — everything there is to know about one locked
 * dependency: what it is, where it came from, what it needs, and where its
 * source lives.
 *
 * <pre>
 *   mjm info "acme:widget"             show the facts (never writes anything)
 *   mjm info "acme:widget" --source    also fetch the source tree and print its path
 * </pre>
 *
 * <p>Source is addressed, not shipped: mjm never publishes a {@code -sources.jar}
 * (Go and Cargo never split source from artifact), but the lockfile pins the
 * exact tag, so the tree is always reachable at a stable URL. Central
 * dependencies — which were published by a binary-first ecosystem — fall back
 * to the publisher's own sources jar when one exists.
 *
 * <p>The read/write split is deliberate: reading facts is free and safe, so
 * {@code mjm info} is pure output; fetching a source tree writes to
 * the sources root, which only {@code --source} asks for.
 */
public final class InfoCommand implements Command {

    private static final Pattern RELEASE_DOWNLOAD = Pattern.compile(
            "https?://[^/]+/([^/]+)/([^/]+)/releases/download/([^/]+)/.*");

    private final ArtifactStore store;
    private final Path projectDir;

    @Inject
    public InfoCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "info";
    }

    @Override
    public String usage() {
        return "<id> [--source]";
    }

    @Override
    public String summary() {
        return "identity, origin, dependencies, and source of one artifact";
    }

    @Override
    public int execute(List<String> args) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new InfoCommand(store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean fetchSource = false;
        String id = null;
        for (String arg : args) {
            if (arg.equals("--source")) {
                fetchSource = true;
            } else if (id == null) {
                id = arg;
            } else {
                System.err.println("mjm: info takes one dependency id — mjm info \"group:artifact\"");
                return 2;
            }
        }
        if (id == null) {
            System.err.println("mjm: info needs a dependency id — mjm info \"group:artifact\"");
            return 2;
        }
        final String wanted = id;

        List<LockedArtifact> locked;
        try {
            locked = LockFileReader.read(Files.readString(projectDir.resolve("mjm.lock")));
        } catch (IOException e) {
            System.err.println("mjm: no readable mjm.lock in " + projectDir + " — run mjm lock first");
            return 1;
        }
        LockedArtifact artifact = locked.stream()
                .filter(a -> a.id().equals(wanted))
                .findFirst()
                .orElse(null);
        if (artifact == null) {
            System.err.println("mjm: '" + wanted + "' is not in the closure — mjm why " + wanted
                    + " shows what is");
            return 1;
        }

        Substitutions substitutions = Substitutions.forProject(projectDir);
        System.out.println(artifact.id() + "@" + artifact.version()
                + (artifact.isMain() ? "" : "  [dev]")
                + substitutions.marker(artifact.id()));

        Descriptor descriptor = descriptor(artifact);
        if (descriptor != null) {
            if (descriptor.summary() != null) {
                System.out.println("  " + descriptor.summary());
            }
            if (descriptor.homepage() != null) {
                System.out.println("  homepage: " + descriptor.homepage());
            }
            if (descriptor.license() != null) {
                System.out.println("  license:  " + descriptor.license());
            }
            if (descriptor.javaVersion() != null) {
                System.out.println("  java:     " + descriptor.javaVersion() + "+");
            }
            if (!descriptor.requirements().isEmpty()) {
                System.out.println("  deps:     " + descriptor.requirements().stream()
                        .map(r -> r.id() + "@" + (r.version() == null ? r.tag() : r.version()))
                        .reduce((a, b) -> a + ", " + b).orElse(""));
            }
        }

        System.out.println("  origin:   " + artifact.source());
        System.out.println("  depth:    " + artifact.depth());
        Path localClasses = substitutions.classesOrNull(artifact.id());
        System.out.println("  jar:      " + (localClasses != null
                ? localClasses + "  (local classes)" : artifact.url()));
        String sourcePage = sourcePage(artifact);
        if (sourcePage != null) {
            System.out.println("  source:   " + sourcePage);
        } else if (artifact.source().equals("url")) {
            System.out.println("  source:   (a bare jar URL carries no tag — no source address)");
        }

        if (!fetchSource) {
            if (sourcePage != null || !artifact.source().equals("url")) {
                System.out.println("\nfetch the tree: mjm info \"" + artifact.id() + "\" --source");
            }
            return 0;
        }
        return fetchSource(artifact);
    }

    // ------------------------------------------------------------------
    // descriptor facts
    // ------------------------------------------------------------------

    /** The release descriptor, when this artifact is mj-native; null otherwise. */
    private Descriptor descriptor(LockedArtifact artifact) {
        if (artifact.metaSha256() == null) {
            return null;
        }
        Path meta = store.findByDigest(artifact.metaSha256(), ArtifactStore.Kind.DESCRIPTOR);
        if (meta == null) {
            return null;  // not downloaded yet or a pom-kind metadata row
        }
        try {
            return DescriptorReader.parse(Files.readString(meta),
                    artifact.id().substring(artifact.id().indexOf(':') + 1), artifact.version());
        } catch (IOException | RuntimeException e) {
            return null;  // unreadable metadata never blocks the facts we do have
        }
    }

    // ------------------------------------------------------------------
    // source addressing
    // ------------------------------------------------------------------

    /** The stable, human-browsable source address — null when none exists. */
    private static String sourcePage(LockedArtifact artifact) {
        Matcher matcher = RELEASE_DOWNLOAD.matcher(artifact.url());
        if (matcher.matches()) {
            String host = artifact.url().substring(0, artifact.url().indexOf('/', 8));
            return host + "/" + matcher.group(1) + "/" + matcher.group(2)
                    + "/tree/" + matcher.group(3);
        }
        return null;
    }

    private int fetchSource(LockedArtifact artifact) {
        Path target = sourcesDir().resolve(artifact.id().replace(':', '_')
                + "@" + artifact.version());
        if (artifact.source().startsWith("gh:")) {
            return fetchNativeTree(artifact, target);
        }
        if (artifact.source().equals("url")) {
            System.err.println("mjm: '" + artifact.id() + "' is a bare URL leaf — a jar URL "
                    + "carries no tag, so there is no source tree to fetch");
            return 1;
        }
        Repository repository = artifact.source().equals("central")
                ? null : Repository.parse(artifact.source());
        if (repository != null && repository.layout() == Repository.Layout.MJM) {
            System.err.println("mjm: '" + artifact.id() + "' comes from an mjm-layout repository — "
                    + "this origin has no source tree (it publishes jars only)");
            return 1;
        }
        return fetchCentralSources(artifact, repository, target);
    }

    private int fetchNativeTree(LockedArtifact artifact, Path target) {
        Matcher matcher = RELEASE_DOWNLOAD.matcher(artifact.url());
        if (!matcher.matches()) {
            System.err.println("mjm: cannot read the release coordinates out of " + artifact.url());
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
            System.err.println("mjm: no source archive for " + owner + "/" + repo + "@" + tag
                    + " — " + e.getMessage());
            System.err.println("  browse instead: " + host + "/" + owner + "/" + repo
                    + "/tree/" + tag);
            return 1;
        }
        if (!unpack(tarGz, target)) {
            return 1;
        }
        System.out.println("\nsource → " + target);
        System.out.println("  attach that path as a source root in your IDE");
        return 0;
    }

    private int fetchCentralSources(LockedArtifact artifact, Repository repository, Path target) {
        int colon = artifact.id().indexOf(':');
        if (colon < 0) {
            System.err.println("mjm: '" + artifact.id() + "' is not a group:artifact coordinate");
            return 1;
        }
        String group = artifact.id().substring(0, colon);
        String name = artifact.id().substring(colon + 1);
        String url = ArtifactLocations.mavenFile(
                repository == null
                        ? ArtifactLocations.centralBase() : repository.declaration(),
                group, name, artifact.version(), "sources", "jar");

        Path jar;
        try {
            jar = store.ensure(url, ArtifactStore.Kind.JAR);
        } catch (RuntimeException e) {
            System.err.println("mjm: " + artifact.id() + "@" + artifact.version()
                    + " published no sources jar");
            System.err.println("  this publisher shipped binaries only — read the bytecode with "
                    + "your IDE's decompiler, or ask the publisher for source access");
            return 1;
        }
        if (!unpackJar(jar, target)) {
            return 1;
        }
        System.out.println("\nsource → " + target);
        System.out.println("  attach that path as a source root in your IDE");
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
                System.err.println("mjm: cannot unpack " + tarGz);
                return false;
            }
            return true;
        } catch (IOException e) {
            System.err.println("mjm: cannot run tar — is it on the PATH? " + e.getMessage());
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
                    System.err.println("mjm: the sources jar contains a path escape: "
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
            System.err.println("mjm: cannot read " + jar + ": " + e.getMessage());
            return false;
        }
    }

    /** The sources root ({@link com.jujin.mjm.store.Paths#sources()}) — extracted trees, not pinned bytes. */
    private static Path sourcesDir() {
        String override = System.getProperty("mjm.sources.root");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return com.jujin.mjm.store.Paths.sources();
    }
}
