package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.project.MjProject;
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
 *   mjm publish https://host/     → target/dist/ + Maven layout PUT (needs [project].group)
 * </pre>
 *
 * <p>Authentication: {@code MJM_GITHUB_TOKEN} for gh: targets, and
 * {@code MJM_PUBLISH_TOKEN} (if the host wants one) for plain HTTP hosts.
 * Without a token, the files are generated but not uploaded — the checklist is
 * printed instead.
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
        return "write the release triple and upload it: gh:, mjm:https://host/, or an http(s) host";
    }
    @Override
    public String usage() {
        return "[target]";
    }


    @Override
    public int execute(List<String> args) {
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
        // A dependency in the repository we are publishing to is a sibling: the
        // descriptor can say "gadget, 0.9.0" and the consumer's resolver reads it
        // back as another artifact in that same repository. Anything bound to a
        // different repository stays a rule, and rules do not travel.
        String publishBase = target != null && target.startsWith("mjm:")
                ? ArtifactLocations.canonicalBase(target) : null;
        java.util.Set<String> siblings = new java.util.HashSet<>();
        for (var requirement : project.requirements()) {
            if (requirement.repository() == null) {
                continue;
            }
            String where = ArtifactLocations.canonicalBase(
                    project.repos().get(requirement.repository()));
            if (publishBase != null && publishBase.equals(where)) {
                siblings.add(requirement.id());
                continue;
            }
            System.err.println("mjm: '" + requirement.id() + "' belongs to repository '"
                    + requirement.repository() + "' (" + where + ") — you are publishing to "
                    + (publishBase == null ? target : "mjm:" + publishBase)
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
            System.out.println("upload the three files wherever a URL can reach — a GitHub "
                    + "release, an mjm: repository, an OSS bucket, any static server. "
                    + "then tell a teammate:");
            System.out.println("  mjm add \"" + name + "\" gh:<owner>/" + name + "@v" + version);
            return 0;
        }

        // target given — attempt native upload
        if (target != null && target.startsWith("mjm:")) {
            return uploadToMjmRepository(target, project, dist, jarName, descriptorName, shaName);
        }
        if (target != null && (target.startsWith("https://") || target.startsWith("http://"))) {
            return uploadToHost(target, project, dist, jarName, descriptorName, shaName);
        }
        if (target != null && !target.startsWith("gh:")) {
            System.err.println("mjm: unknown publish target '" + target
                    + "' — use gh:owner/repo, mjm:https://host/, or an http(s) base URL");
            return 1;
        }

        String token = System.getenv("MJM_GITHUB_TOKEN");
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
    // http(s) host — Maven layout, HTTP PUT
    // ------------------------------------------------------------------

    /**
     * Publishes the triple to an arbitrary http(s) host using Maven's
     * repository layout: {@code base/group/path/artifact/version/artifact-version.ext}.
     * The same layout {@code [repos]} reads, so a library published here is
     * consumed with nothing more than a {@code [repos]} entry — no platform,
     * no account, no registry.
     *
     * <p>Upload is HTTP PUT (WebDAV semantics). It works against any server
     * that accepts PUT — nginx with dav_module, Apache mod_dav, most object
     * storage gateways. mjm speaks exactly one upload protocol on purpose: it
     * cannot know every host's API, and inventing an adapter per backend is
     * how a build tool becomes a deployment platform.
     *
     * <p>Requires {@code [project].group}: the layout is group-based, and
     * without one there is no path to write to.
     */
    private int uploadToHost(String base, MjProject project, Path dist,
                             String jarName, String descriptorName, String shaName) {
        if (project.group() == null) {
            System.err.println("mjm: publishing to " + base + " needs [project].group — "
                    + "a Maven layout is group-based (group/path/artifact/version/)");
            return 1;
        }
        String root = base.endsWith("/") ? base : base + "/";
        String directory = project.group().replace('.', '/') + "/" + project.name()
                + "/" + project.version() + "/";

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

        System.out.println("published to " + base + " — consume it with:");
        System.out.println("  [repos]");
        System.out.println("  mine = \"" + root + "\"");
        System.out.println("  [deps.mine]");
        System.out.println("  \"" + project.group() + ":" + project.name() + "\" = \""
                + project.version() + "\"");
        return 0;
    }

    /**
     * Publishes to an {@code mjm:} repository — two levels, no group:
     * {@code <base>/<name>/<version>/<name>-<version>.{jar,mjm.toml,sha256}}.
     * No {@code [project].group} is needed: that is the point of the layout.
     */
    private int uploadToMjmRepository(String target, MjProject project, Path dist,
                                      String jarName, String descriptorName, String shaName) {
        String root = ArtifactLocations.baseUrl(target.substring("mjm:".length()));
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

    /** One PUT, treating 200/201/204 as success. */
    private void put(String url, byte[] bytes) throws IOException, InterruptedException {
        String token = System.getenv("MJM_PUBLISH_TOKEN");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status != 200 && status != 201 && status != 204) {
            throw new IOException("server answered " + status
                    + (response.body().isBlank() ? "" : ": " + response.body().substring(
                            0, Math.min(200, response.body().length()))));
        }
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
            releaseId = createRelease(host, origin, owner, repo, tag, token);
        } catch (IOException | InterruptedException e) {
            System.err.println("mjm: cannot create the release: " + e.getMessage());
            return 1;
        }

        for (Path file : files) {
            try {
                uploadFile(owner, repo, releaseId, file, token);
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

    /** Creates a release (or reuses the existing one) and returns its platform id. */
    private String createRelease(String host, String origin, String owner, String repo,
                                 String tag, String token)
            throws IOException, InterruptedException {
        String url = "https://api.github.com/repos/"
                + origin.substring(origin.indexOf(':') + 1) + "/releases";
        String body = "{\"tag_name\":\"" + tag + "\",\"name\":\"" + tag
                + "\",\"body\":\"released with mjm publish\"}";

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer " + token);

        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            var map = JsonUtils.parseObject(response.body()).toMap();
            return String.valueOf(map.get("id"));
        }
        // release might already exist — attach to it (one repo, many modules
        // of the same version share one release)
        if (response.statusCode() == 422 || response.statusCode() == 409) {
            String existing = findReleaseId(owner, repo, tag, token);
            if (existing != null) {
                System.out.println("release " + tag + " already exists on " + origin
                        + " — attaching to it");
                return existing;
            }
            throw new IOException("release " + tag + " already exists on " + origin
                    + " but could not be looked up — use a different version or "
                    + "delete the existing release");
        }
        throw new IOException("release creation failed (" + response.statusCode() + "): "
                + response.body().substring(0, Math.min(200, response.body().length())));
    }

    /** Looks up an existing release by tag; null when none is reachable. */
    private String findReleaseId(String owner, String repo, String tag, String token)
            throws IOException, InterruptedException {
        String url = "https://api.github.com/repos/" + owner + "/" + repo
                + "/releases/tags/" + tag;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET()
                .header("Authorization", "Bearer " + token);
        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        String body = response.body();
        try {
            var map = JsonUtils.parseObject(body).toMap();
            return map.get("id") == null ? null : String.valueOf(map.get("id"));
        } catch (RuntimeException notAnObject) {
            // some platforms answer an array for the by-tag lookup
            try {
                var array = JsonUtils.parseArray(body);
                if (!array.isEmpty() && array.get(0) instanceof JsonObject first
                        && first.toMap().get("id") != null) {
                    return String.valueOf(first.toMap().get("id"));
                }
            } catch (RuntimeException ignored) {
                // fall through
            }
            return null;
        }
    }

    /** Uploads one file as a GitHub release asset. */
    private void uploadFile(String owner, String repo, String releaseId, Path file, String token)
            throws IOException, InterruptedException {
        byte[] bytes = Files.readAllBytes(file);
        String fileName = file.getFileName().toString();
        String url = "https://uploads.github.com/repos/" + owner + "/" + repo
                + "/releases/" + releaseId + "/assets?name=" + fileName;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IOException("upload of " + fileName + " returned "
                    + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(200, response.body().length())));
        }
        System.out.println("  uploaded " + fileName);
    }

    /** The base an mjm: declaration points at, normalized with a trailing slash. */
    private static String mjmBase(String declaration) {
        String host = declaration != null && declaration.startsWith("mjm:")
                ? declaration.substring("mjm:".length())
                : declaration;
        return ArtifactLocations.baseUrl(host);
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
