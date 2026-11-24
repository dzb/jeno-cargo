package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.jeno.project.Descriptor;
import com.jujin.jeno.project.DescriptorReader;
import com.jujin.jeno.project.Repository;
import com.jujin.jeno.resolve.ArtifactLocations;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code jeno info <id>} — everything there is to know about one locked
 * dependency: what it is, where it came from, what it needs, and where its
 * source lives.
 *
 * <pre>
 *   jeno info "acme:widget"             show the facts (never writes anything)
 *   jeno info "acme:widget" --source    also fetch the source tree and print its path
 * </pre>
 *
 * <p>Source is addressed, not shipped: jeno never publishes a {@code -sources.jar}
 * (Go and Cargo never split source from artifact), but the lockfile pins the
 * exact tag, so the tree is always reachable at a stable URL. Central
 * dependencies — which were published by a binary-first ecosystem — fall back
 * to the publisher's own sources jar when one exists.
 *
 * <p>The read/write split is deliberate: reading facts is free and safe, so
 * {@code jeno info} is pure output; fetching a source tree writes to
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
    public Command.Category category() {
        return Command.Category.INSPECT;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.OPTIONAL)
                .writes(CommandCapabilities.WriteScope.CACHE,
                        CommandCapabilities.WriteScope.SOURCES)
                .workspace(CommandCapabilities.WorkspaceMode.MODULE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        ProjectFiles.rejectLegacy(projectDir);
        boolean json = args.contains("--json");
        if (json && Workspaces.rootAt(projectDir).isPresent() && !hasModuleOption(args)) {
            JsonResult.error("info", 2, "MODULE_REQUIRED",
                    "this directory is a workspace root — say which module: --module <path>")
                    .emit(2);
            return 2;
        }
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new InfoCommand(store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean fetchSource = false;
        String id = null;
        for (String arg : args) {
            if (arg.equals("--json")) {
                continue;
            } else if (arg.equals("--source")) {
                fetchSource = true;
            } else if (id == null) {
                id = arg;
            } else {
                String message = "info takes one dependency id — jeno info \"group:artifact\"";
                if (json) {
                    JsonResult.error("info", 2, "USAGE", message).emit(2);
                } else {
                    System.err.println(Ui.errPrefix() + message);
                }
                return 2;
            }
        }
        if (id == null) {
            String message = "info needs a dependency id — jeno info \"group:artifact\"";
            if (json) {
                JsonResult.error("info", 2, "USAGE", message).emit(2);
            } else {
                System.err.println(Ui.errPrefix() + message);
            }
            return 2;
        }
        if (json && fetchSource) {
            JsonResult.error("info", 2, "JSON_SOURCE_UNSUPPORTED",
                    "info --source is not machine-readable yet; run info --json without --source")
                    .emit(2);
            return 2;
        }
        final String wanted = id;

        List<LockedArtifact> locked;
        try {
            locked = LockFileReader.read(Files.readString(projectDir.resolve("jeno.lock")));
        } catch (IOException e) {
            String message = "no readable jeno.lock in " + projectDir + " — run jeno lock first";
            if (json) {
                JsonResult.error("info", 1, "LOCK_UNREADABLE", message).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + message);
            }
            return 1;
        } catch (LegacyNames.LegacyException e) {
            throw e;
        } catch (RuntimeException e) {
            if (json) {
                JsonResult.error("info", 1, "LOCK_INVALID", e.getMessage()).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + e.getMessage());
            }
            return 1;
        }
        LockedArtifact artifact = locked.stream()
                .filter(a -> a.id().equals(wanted))
                .findFirst()
                .orElse(null);
        if (artifact == null) {
            String message = "'" + wanted + "' is not in the closure — jeno why " + wanted
                    + " shows what is";
            if (json) {
                JsonResult.error("info", 1, "ARTIFACT_NOT_FOUND", message).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + message);
            }
            return 1;
        }

        Substitutions substitutions = Substitutions.forProject(projectDir);
        if (json) {
            emitJson(artifact, substitutions);
            return 0;
        }
        String scope = switch (artifact.scope()) {
            case MAIN -> "";
            case DEV -> "  [dev]";
            case PROCESSOR -> "  [processor]";
        };
        System.out.println(Ui.strong(artifact.id() + "@" + artifact.version())
                + Ui.dim(scope)
                + Ui.accent(substitutions.marker(artifact.id())));

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
                        .map(r -> r.id() + "@" + (r.version() == null ? r.tag() : r.version())
                                + (descriptor.apiRequirements().contains(r.id()) ? " [api]" : ""))
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
                System.out.println("\nfetch the tree: jeno info \"" + artifact.id() + "\" --source");
            }
            return 0;
        }
        return fetchSource(artifact);
    }

    // ------------------------------------------------------------------
    // JSON export
    // ------------------------------------------------------------------

    /** A dependency-consumer view; context-only fields stay under context. */
    private void emitJson(LockedArtifact artifact, Substitutions substitutions) {
        int colon = artifact.id().indexOf(':');
        String group = colon > 0 ? artifact.id().substring(0, colon) : null;
        String name = colon > 0 ? artifact.id().substring(colon + 1) : artifact.id();

        JsonObject identity = JsonUtils.object();
        identity.put("id", artifact.id());
        if (group != null) {
            identity.put("group", group);
        }
        identity.put("artifact", name);
        identity.put("version", artifact.version());
        identity.put("sha256", artifact.sha256());
        if (artifact.metaSha256() != null) {
            identity.put("metaSha256", artifact.metaSha256());
        }

        JsonObject provenance = JsonUtils.object();
        provenance.put("source", artifact.source());
        provenance.put("url", artifact.url());
        provenance.put("readableUrl", artifact.readableUrl(projectDir));
        repositoryFacts(provenance, artifact.source());
        Path storedJar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
        JsonObject jar = JsonUtils.object();
        if (storedJar != null) {
            jar.put("path", storedJar.toAbsolutePath().normalize().toString());
        }
        Path localClasses = substitutions.classesOrNull(artifact.id());
        if (localClasses != null) {
            jar.put("localPath", localClasses.toAbsolutePath().normalize().toString());
            jar.put("local", true);
        } else {
            jar.put("local", false);
        }
        provenance.put("jar", jar);

        MetadataRef metadata = metadata(artifact);
        if (metadata != null) {
            JsonObject meta = JsonUtils.object();
            meta.put("kind", metadata.kind().name().toLowerCase(java.util.Locale.ROOT));
            meta.put("sha256", artifact.metaSha256());
            meta.put("path", metadata.path().toAbsolutePath().normalize().toString());
            provenance.put("metadata", meta);
        }

        JsonObject context = JsonUtils.object();
        context.put("scope", artifact.scope().name().toLowerCase(java.util.Locale.ROOT));
        context.put("depth", artifact.depth());
        context.put("local", localClasses != null);
        context.put("marker", substitutions.marker(artifact.id()));

        JsonObject source = JsonUtils.object();
        String page = sourcePage(artifact);
        if (page != null) {
            source.put("page", page);
        }
        String archive = sourceArchive(artifact);
        if (archive != null) {
            source.put("archive", archive);
        }
        source.put("fetched", false);

        JsonResult result = JsonResult.start("info")
                .workspace(projectDir, "single")
                .put("artifact", identity)
                .put("provenance", provenance)
                .put("context", context);
        Descriptor descriptor = descriptor(artifact);
        if (descriptor != null) {
            result.put("descriptor", descriptorJson(descriptor));
        }
        if (!source.isEmpty()) {
            result.put("source", source);
        }
        result.emit(0);
    }

    private static JsonObject descriptorJson(Descriptor descriptor) {
        JsonObject result = JsonUtils.object();
        result.put("name", descriptor.name());
        if (descriptor.group() != null) {
            result.put("group", descriptor.group());
        }
        result.put("version", descriptor.version());
        if (descriptor.javaVersion() != null) {
            result.put("javaVersion", descriptor.javaVersion());
        }
        if (descriptor.summary() != null) {
            result.put("summary", descriptor.summary());
        }
        if (descriptor.homepage() != null) {
            result.put("homepage", descriptor.homepage());
        }
        if (descriptor.license() != null) {
            result.put("license", descriptor.license());
        }
        JsonArray dependencies = JsonUtils.array();
        for (com.jujin.jeno.project.Requirement requirement : descriptor.requirements()) {
            JsonObject dependency = JsonUtils.object();
            dependency.put("id", requirement.id());
            dependency.put("species", requirement.species().name()
                    .toLowerCase(java.util.Locale.ROOT));
            if (requirement.version() != null) {
                dependency.put("version", requirement.version());
            }
            if (requirement.tag() != null) {
                dependency.put("tag", requirement.tag());
            }
            if (requirement.repository() != null) {
                dependency.put("repository", requirement.repository());
            }
            if (requirement.classifier() != null) {
                dependency.put("classifier", requirement.classifier());
            }
            dependency.put("api", descriptor.apiRequirements().contains(requirement.id()));
            dependencies.add(dependency);
        }
        result.put("dependencies", dependencies);
        if (!descriptor.retract().isEmpty()) {
            JsonObject retract = JsonUtils.object();
            descriptor.retract().forEach(retract::put);
            result.put("retract", retract);
        }
        return result;
    }

    private static void repositoryFacts(JsonObject provenance, String source) {
        if (source.equals("central")) {
            provenance.put("repository", "central");
            provenance.put("layout", "maven");
            return;
        }
        if (source.equals("url")) {
            provenance.put("layout", "plain-url");
            return;
        }
        if (source.startsWith("gh:")) {
            provenance.put("repository", source);
            provenance.put("layout", "github-release");
            provenance.put("transport", "https");
            return;
        }
        try {
            Repository repository = Repository.parse(source);
            provenance.put("repository", repository.declaration());
            provenance.put("layout", repository.layout().name()
                    .toLowerCase(java.util.Locale.ROOT));
            provenance.put("transport", repository.transport().name()
                    .toLowerCase(java.util.Locale.ROOT));
        } catch (LegacyNames.LegacyException e) {
            throw e;
        } catch (RuntimeException e) {
            provenance.put("layout", "unknown");
        }
    }

    private MetadataRef metadata(LockedArtifact artifact) {
        if (artifact.metaSha256() == null) {
            return null;
        }
        for (ArtifactStore.Kind kind : List.of(
                ArtifactStore.Kind.DESCRIPTOR, ArtifactStore.Kind.POM)) {
            Path path = store.findByDigest(artifact.metaSha256(), kind);
            if (path != null) {
                return new MetadataRef(kind, path);
            }
        }
        return null;
    }

    private static String sourceArchive(LockedArtifact artifact) {
        Matcher matcher = RELEASE_DOWNLOAD.matcher(artifact.url());
        if (!matcher.matches()) {
            return null;
        }
        String host = artifact.url().substring(0, artifact.url().indexOf('/', 8));
        return host + "/" + matcher.group(1) + "/" + matcher.group(2)
                + "/archive/refs/tags/" + matcher.group(3) + ".tar.gz";
    }

    private record MetadataRef(ArtifactStore.Kind kind, Path path) {}

    private static boolean hasModuleOption(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("--module") || arg.startsWith("--module="));
    }

    // ------------------------------------------------------------------
    // descriptor facts
    // ------------------------------------------------------------------

    /** The release descriptor, when this artifact is jeno-native; null otherwise. */
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
            System.err.println(Ui.errPrefix() + "'" + artifact.id() + "' is a bare URL leaf — a jar URL "
                    + "carries no tag, so there is no source tree to fetch");
            return 1;
        }
        Repository repository = artifact.source().equals("central")
                ? null : Repository.parse(artifact.source());
        if (repository != null && repository.layout() == Repository.Layout.JENO) {
            System.err.println(Ui.errPrefix() + "'" + artifact.id() + "' comes from an jeno-layout repository — "
                    + "this origin has no source tree (it publishes jars only)");
            return 1;
        }
        return fetchCentralSources(artifact, repository, target);
    }

    private int fetchNativeTree(LockedArtifact artifact, Path target) {
        Matcher matcher = RELEASE_DOWNLOAD.matcher(artifact.url());
        if (!matcher.matches()) {
            System.err.println(Ui.errPrefix() + "cannot read the release coordinates out of " + artifact.url());
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
        } catch (LegacyNames.LegacyException e) {
            throw e;
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + "no source archive for " + owner + "/" + repo + "@" + tag
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
            System.err.println(Ui.errPrefix() + "'" + artifact.id() + "' is not a group:artifact coordinate");
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
        } catch (LegacyNames.LegacyException e) {
            throw e;
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + artifact.id() + "@" + artifact.version()
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
                System.err.println(Ui.errPrefix() + "cannot unpack " + tarGz);
                return false;
            }
            return true;
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot run tar — is it on the PATH? " + e.getMessage());
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
                    System.err.println(Ui.errPrefix() + "the sources jar contains a path escape: "
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
            System.err.println(Ui.errPrefix() + "cannot read " + jar + ": " + e.getMessage());
            return false;
        }
    }

    /** The sources root ({@link com.jujin.jeno.store.Paths#sources()}) — extracted trees, not pinned bytes. */
    private static Path sourcesDir() {
        String override = System.getProperty("jeno.sources.root");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return com.jujin.jeno.store.Paths.sources();
    }
}
