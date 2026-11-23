package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.Repository;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.project.Requirement;
import com.jujin.mjm.project.Species;
import com.jujin.mjm.resolve.ArtifactLocations;
import com.jujin.mjm.store.ArtifactStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code mjm publish [target]} — the decentralized release (DESIGN.md §7.2).
 *
 * <p>Generates the three-piece artifact into target/dist/, then — when a target is
 * given — uploads it via the platform's release API:
 *
 * <pre>
 *   mjm publish                    → target/dist/ + upload hint
 *   mjm publish gh:owner/repo     → target/dist/ + GitHub native upload
 *   mjm publish mjm:https://host/ → target/dist/ + mjm layout PUT (no group needed)
 *   mjm publish mjm:local:<dir>   → target/dist/ + the same layout on disk
 * </pre>
 *
 * <p>A Maven-layout target ({@code https://host/}, {@code local:<dir>}) is
 * refused: mjm writes no POM, so neither mjm nor Maven could read back the
 * triple. Reading Maven repositories stays supported; only publishing to one
 * is not.
 *
 * <p>Authentication: {@code MJM_GITHUB_TOKEN} for gh: targets, and
 * {@code MJM_PUBLISH_TOKEN} for mjm-layout hosts that want one. Without a
 * token, the files are generated but not uploaded — the checklist is printed
 * instead.
 */
public final class PublishCommand implements Command {

    private static final String CLASSES = "target/classes";

    private final Path projectDir;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Inject
    public PublishCommand(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public boolean supportsOffline() {
        return false;
    }

    @Override
    public String summary() {
        return "write the release triple and upload it: gh:, mjm:https://host/, mjm:local:<dir>";
    }

    @Override
    public String help() {
        return """
               targets mjm can read back: gh:<owner>/<repo>, mjm:https://host/,
               mjm:local:<dir>; no target only prepares target/dist/.
               a Maven-layout target (https://host/, local:<dir>) is refused:
               mjm writes no POM, so neither mjm nor Maven could consume it.""";
    }
    @Override
    public String usage() {
        return "[target]";
    }


    @Override
    public int execute(List<String> args) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new PublishCommand(dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        if (args.size() > 1) {
            System.err.println("mjm: publish takes at most one target — got '" + args.get(1) + "'");
            return 2;
        }
        // The upload reads the bytes in target/dist/, so the lock covers the network
        // phase too: locking only the write phase would let clean --dist pull a
        // jar out from under a running upload. The cost is that another writer
        // waits for the upload — on purpose (REMINDS 13).
        return ProjectLock.with(projectDir, () -> publish(args));
    }

    private int publish(List<String> args) {
        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
        } catch (IOException e) {
            System.err.println("mjm: no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        }

        String name = project.name();
        String version = project.version();
        String jarName = name + "-" + version + ".jar";
        String descriptorName = name + "-" + version + ".mjm.toml";
        String shaName = name + "-" + version + ".sha256";

        Path classes = projectDir.resolve(CLASSES);
        if (!Files.isDirectory(classes)) {
            System.err.println("mjm: " + CLASSES + " is missing — run mjm build first");
            return 1;
        }

        String target = args.isEmpty() ? null : args.getFirst();
        // Only targets mjm itself can read back. Maven layouts are refused
        // before anything is written: mjm emits no POM, so neither mjm nor
        // Maven could consume what it just uploaded.
        Repository publishTarget = null;
        if (target != null && !target.startsWith("gh:")) {
            try {
                publishTarget = Repository.parse(target);
            } catch (IllegalArgumentException e) {
                System.err.println("mjm: " + e.getMessage());
                return 1;
            }
            if (publishTarget.layout() == Repository.Layout.MAVEN) {
                System.err.println("mjm: cannot publish to a Maven-layout target — mjm writes "
                        + "no POM, so neither mjm nor Maven can read it back; use "
                        + "gh:<owner>/<repo> or mjm:https://host/ (or mjm:local:<dir>)");
                return 1;
            }
            if (publishTarget.isPlainHttp() && !publishTarget.isLoopbackNetwork()) {
                System.err.println("warning: publishing over plain HTTP ("
                        + publishTarget.declaration() + ") — the traffic and MJM_PUBLISH_TOKEN"
                        + " are not encrypted");
            }
        }
        // A dependency in the repository we are publishing to is a sibling: the
        // descriptor can say "gadget, 0.9.0" and the consumer's resolver reads it
        // back as another artifact in that same repository. Anything bound to a
        // different repository stays a rule, and rules do not travel.
        String publishBase = publishTarget != null && publishTarget.isMjmLayout()
                ? ArtifactLocations.canonicalLocation(publishTarget) : null;
        java.util.Set<String> siblings = new java.util.HashSet<>();
        for (var requirement : project.requirements()) {
            if (requirement.repository() == null) {
                continue;
            }
            Repository declared = project.repos().get(requirement.repository());
            String where = ArtifactLocations.canonicalLocation(declared);
            if (publishBase != null && publishBase.equals(where)) {
                siblings.add(requirement.id());
                continue;
            }
            System.err.println("mjm: '" + requirement.id() + "' belongs to repository '"
                    + requirement.repository() + "' (" + where + ") — you are publishing to "
                    + target
                    + "; a descriptor can only name siblings in the repository it is "
                    + "published to. Move that dependency to Central/GitHub, publish it to "
                    + "this same repository first, or drop it");
            return 1;
        }

        if (!Files.isDirectory(projectDir.resolve("target/dist"))
                && DistOutput.legacyPresent(projectDir)) {
            System.err.println("mjm: deliverables moved to target/dist — the old dist/ is "
                    + "no longer read; rerun mjm package (or bundle) to write the new location");
            return 1;
        }

        Path dist;
        try {
            dist = DistOutput.dist(projectDir);
            // clear only what publish writes. The jar belongs to `mjm package`
            // — publishing does not regenerate it, and deleting it here would
            // make the two commands fight over the same path.
            for (String file : List.of(descriptorName, shaName)) {
                DistOutput.clear(dist.resolve(file));
            }
        } catch (IOException e) {
            System.err.println("mjm: cannot prepare target/dist/: " + e.getMessage());
            return 1;
        }

        // publish uploads what package built — it never builds its own jar.
        // Building one here would silently overwrite mjm package's output with a
        // different (manifest-less) jar, and a release would then carry bytes
        // nobody reviewed.
        Path jarFile = dist.resolve(jarName);
        if (!Files.exists(jarFile)) {
            System.err.println("mjm: " + jarFile + " is missing — run mjm package first "
                    + "(publish uploads what package builds; it does not build its own jar)");
            return 1;
        }

        try {
            Files.writeString(dist.resolve(descriptorName), descriptor(project, siblings));
            Files.writeString(dist.resolve(shaName),
                    ArtifactStore.sha256(jarFile) + "\n");
        } catch (IOException e) {
            System.err.println("mjm: cannot write the descriptor or sha256 into target/dist/");
            return 1;
        }

        System.out.println("published the release triple into target/dist/:");
        System.out.println("  " + jarFile);
        System.out.println("  " + dist.resolve(descriptorName));
        System.out.println("  " + dist.resolve(shaName));

        if (args.isEmpty()) {
            System.out.println("upload the three files to a GitHub release or an mjm: "
                    + "repository, then tell a teammate:");
            System.out.println("  mjm add \"" + name + "\" gh:<owner>/" + name + "@v" + version);
            System.out.println("  (or publish mjm:https://host/ to host it yourself)");
            return 0;
        }

        // target given — only the mjm layout is allowed by this point
        if (publishTarget != null) {
            return publishTarget.isLocal()
                    ? publishToLocalMjmRepository(publishTarget, project, dist,
                            jarName, descriptorName, shaName)
                    : uploadToMjmRepository(publishTarget, project, dist,
                            jarName, descriptorName, shaName);
        }

        String token = GitHubReleases.token();
        if (token == null || token.isBlank()) {
            System.err.println("mjm: MJM_GITHUB_TOKEN environment variable is not set — "
                    + "set it to your GitHub personal access token, or run without the "
                    + "target to get the upload checklist");
            return 1;
        }

        System.out.println("uploading to " + target + " …");
        return upload(target, token, project, dist, jarName, descriptorName, shaName);
    }

    // ------------------------------------------------------------------
    // mjm-layout uploads — HTTP PUT or a local directory
    // ------------------------------------------------------------------

    /**
     * Publishes the triple to an mjm-layout repository over HTTP:
     * {@code <base>/<name>/<version>/<name>-<version>.{jar,mjm.toml,sha256}}.
     * The same two-level layout {@code [repos]} reads for an {@code mjm:}
     * entry, so a library published here needs no platform, account or registry
     * — and no {@code [project].group}.
     *
     * <p>Upload is HTTP PUT (WebDAV semantics), one file per request. A host
     * that accepts PUT is enough (nginx with dav_module, Apache mod_dav, most
     * object-storage gateways); {@code MJM_PUBLISH_TOKEN}, when set, is sent as
     * a bearer token. mjm speaks exactly one upload protocol on purpose — and
     * it writes no POM, which is why a Maven-layout target is not accepted at
     * all.
     */
    private int uploadToMjmRepository(Repository repository, MjProject project, Path dist,
                                      String jarName, String descriptorName, String shaName) {
        String target = repository.declaration();
        String root = repository.networkBase();
        String directory = project.name() + "/" + project.version() + "/";

        List<Path> files = List.of(
                dist.resolve(jarName),
                dist.resolve(descriptorName),
                dist.resolve(shaName));
        for (Path file : files) {
            String url = root + directory + file.getFileName();
            try {
                put(url, Files.readAllBytes(file));
            } catch (IOException | InterruptedException e) {
                System.err.println("mjm: PUT " + url + " failed — " + e.getMessage());
                System.err.println("  the host must accept HTTP PUT (WebDAV); a plain static "
                        + "server rejects it");
                return 1;
            }
            System.out.println("  PUT " + url);
        }

        System.out.println("published to " + target + " — consume it with:");
        System.out.println("  [repos]");
        System.out.println("  mine = \"" + target + "\"");
        System.out.println("  \"" + project.name() + "\" = \"mine:" + project.version() + "\"");
        return 0;
    }

    private int publishToLocalMjmRepository(Repository repository, MjProject project, Path dist,
                                            String jarName, String descriptorName,
                                            String shaName) {
        String target = repository.declaration();
        Path directory;
        try {
            directory = ArtifactLocations.localDirectory(repository)
                    .resolve(project.name()).resolve(project.version());
        } catch (RuntimeException e) {
            System.err.println("mjm: cannot resolve " + target + " — " + e.getMessage());
            return 1;
        }

        List<Path> files = List.of(
                dist.resolve(jarName),
                dist.resolve(descriptorName),
                dist.resolve(shaName));
        try {
            Files.createDirectories(directory);
            for (Path file : files) {
                writeAtomically(directory.resolve(file.getFileName()),
                        Files.readAllBytes(file));
            }
        } catch (IOException e) {
            System.err.println("mjm: cannot write " + directory + " — " + e.getMessage());
            return 1;
        }
        for (Path file : files) {
            System.out.println("  " + directory.resolve(file.getFileName()));
        }

        System.out.println("published to " + target + " — consume it with:");
        System.out.println("  [repos]");
        System.out.println("  mine = \"" + target + "\"");
        System.out.println("  \"" + project.name() + "\" = \"mine:" + project.version() + "\"");
        return 0;
    }

    /** Writes beside the target and moves it into place, so a reader never sees half a file. */
    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".mjm-publish-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** One PUT, treating 200/201/204 as success. */
    private void put(String url, byte[] bytes) throws IOException, InterruptedException {
        MjmUploads.put(url, bytes);
    }

    // ------------------------------------------------------------------
    // native upload
    // ------------------------------------------------------------------

    private int upload(String target, String token, MjProject project,
                       Path dist, String jarName, String descriptorName, String shaName) {
        String origin = target;
        String host = ArtifactLocations.githubHost();
        String owner = origin.substring(origin.indexOf(':') + 1, origin.indexOf('/'));
        String repo = origin.substring(origin.indexOf('/') + 1);
        String version = project.version();
        String tag = "v" + version;

        List<Path> files = List.of(
                dist.resolve(project.name() + "-" + version + ".jar"),
                dist.resolve(project.name() + "-" + version + ".mjm.toml"),
                dist.resolve(project.name() + "-" + version + ".sha256"));

        String releaseId;
        try {
            releaseId = GitHubReleases.create(origin, owner, repo, tag, token,
                    "{\"tag_name\":\"" + tag + "\",\"name\":\"" + tag
                            + "\",\"body\":\"released with mjm publish\"}");
        } catch (IOException | InterruptedException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }

        for (Path file : files) {
            try {
                GitHubReleases.upload(owner, repo, releaseId, file, token);
            } catch (IOException | InterruptedException e) {
                System.err.println("mjm: upload failed for " + file.getFileName() + ": "
                        + e.getMessage());
                return 1;
            }
        }

        System.out.println("uploaded; share it with:");
        System.out.println("  mjm add \"" + project.name() + "\" " + target + "@" + tag);

        System.out.println("  " + host + "/" + owner + "/" + repo + "/releases/tag/" + tag);
        return 0;
    }

    private static String descriptor(MjProject project, java.util.Set<String> siblings) {
        StringBuilder out = new StringBuilder();
        out.append("[project]\n");
        out.append("name = \"").append(project.name()).append("\"\n");
        if (project.group() != null) {
            out.append("group = \"").append(escape(project.group())).append("\"\n");
        }
        out.append("version = \"").append(project.version()).append("\"\n");
        // identity facts travel with the release: they are what `mjm info`
        // shows a stranger who is deciding whether to depend on this
        if (project.summary() != null) {
            out.append("summary = \"").append(escape(project.summary())).append("\"\n");
        }
        if (project.homepage() != null) {
            out.append("homepage = \"").append(escape(project.homepage())).append("\"\n");
        }
        if (project.license() != null) {
            out.append("license = \"").append(escape(project.license())).append("\"\n");
        }
        if (project.javaVersion() != null) {
            out.append("\n[java]\nversion = ").append(project.javaVersion()).append('\n');
        }
        if (!project.requirements().isEmpty()) {
            out.append("\n[deps]\n");
            for (Requirement requirement : project.requirements()) {
                out.append("\"").append(requirement.id()).append("\" = \"")
                        .append(requirementValue(requirement, siblings.contains(requirement.id())))
                        .append("\"\n");
            }
        }
        return out.toString();
    }

    /** TOML basic-string escaping for the identity facts (quotes, backslashes). */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requirementValue(Requirement requirement, boolean sibling) {
        return switch (requirement.species()) {
            case CENTRAL -> requirement.version();
            case GH -> requirement.origin() + "@" + requirement.tag();
            case URL -> requirement.url();
            // the bare name/version a descriptor is allowed to state; the
            // resolver reads it back as an artifact of the publishing repository
            case NAMED -> {
                if (!sibling) {
                    throw new IllegalStateException(
                            "a non-sibling repo-bound dependency never reaches a descriptor");
                }
                yield requirement.version();
            }
        };
    }


}
