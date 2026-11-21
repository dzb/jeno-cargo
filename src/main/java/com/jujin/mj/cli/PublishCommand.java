package com.jujin.mj.cli;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.project.MjProject;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.project.Requirement;
import com.jujin.mj.project.Species;
import com.jujin.mj.resolve.ArtifactLocations;
import com.jujin.mj.store.ArtifactStore;

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
 * {@code mj publish [target]} — the decentralized release (DESIGN.md §7.2).
 *
 * <p>Generates the three-piece artifact into dist/, then — when a target is
 * given — uploads it via the platform's release API:
 *
 * <pre>
 *   mj publish                    → dist/ + upload hint
 *   mj publish gh:owner/repo     → dist/ + GitHub native upload
 *   mj publish gitee:owner/repo  → dist/ + Gitee native upload
 * </pre>
 *
 * <p>Authentication: {@code MJ_GITHUB_TOKEN} / {@code MJ_GITEE_TOKEN} env
 * vars. Without a token, the files are generated but not uploaded — the
 * checklist is printed instead.
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
    public String summary() {
        return "generate the release triple into dist/ (+ native upload for gh:/gitee:)";
    }

    @Override
    public int execute(List<String> args) {
        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mj.toml")));
        } catch (IOException e) {
            System.err.println("mj: cannot read mj.toml in " + projectDir);
            return 1;
        }

        String name = project.name();
        String version = project.version();
        String jarName = name + "-" + version + ".jar";
        String descriptorName = name + "-" + version + ".mj.toml";
        String shaName = name + "-" + version + ".sha256";

        Path classes = projectDir.resolve(CLASSES);
        if (!Files.isDirectory(classes)) {
            System.err.println("mj: " + CLASSES + " is missing — run mj build first");
            return 1;
        }

        for (var requirement : project.requirements()) {
            if (requirement.species() == Species.CENTRAL && requirement.repository() != null) {
                System.err.println("mj: '" + requirement.id() + "' is bound to repository '"
                        + requirement.repository() + "' — descriptors carry Central, gh:, gitee: "
                        + "and URL dependencies only; publish that dependency to the same host "
                        + "as this library, or drop it");
                return 1;
            }
        }

        Path dist = projectDir.resolve("dist");
        try {
            Files.createDirectories(dist);
        } catch (IOException e) {
            System.err.println("mj: cannot create dist/");
            return 1;
        }

        Path jarFile = dist.resolve(jarName);
        int exit = jar(classes, jarFile);
        if (exit != 0) {
            return exit;
        }

        try {
            Files.writeString(dist.resolve(descriptorName), descriptor(project));
            Files.writeString(dist.resolve(shaName),
                    ArtifactStore.sha256(jarFile) + "\n");
        } catch (IOException e) {
            System.err.println("mj: cannot write the descriptor or sha256 into dist/");
            return 1;
        }

        System.out.println("published the release triple into dist/:");
        System.out.println("  " + jarFile);
        System.out.println("  " + dist.resolve(descriptorName));
        System.out.println("  " + dist.resolve(shaName));

        if (args.isEmpty()) {
            System.out.println("upload the three files wherever a URL can reach — a GitHub/Gitee "
                    + "release, an OSS bucket, any static server. then tell a teammate:");
            System.out.println("  mj add \"" + name + "\" gh:<owner>/" + name + "@v" + version);
            return 0;
        }

        // target given — attempt native upload
        String target = args.getFirst();
        if (!target.startsWith("gh:") && !target.startsWith("gitee:")) {
            System.err.println("mj: unknown publish target '" + target
                    + "' — use gh:owner/repo or gitee:owner/repo");
            return 1;
        }

        String tokenEnv = target.startsWith("gh:") ? "MJ_GITHUB_TOKEN" : "MJ_GITEE_TOKEN";
        String token = System.getenv(tokenEnv);
        if (token == null || token.isBlank()) {
            System.err.println("mj: " + tokenEnv + " environment variable is not set — "
                    + "set it to your " + (target.startsWith("gh:") ? "GitHub" : "Gitee")
                    + " personal access token, or run without the target to get the "
                    + "upload checklist");
            return 1;
        }

        System.out.println("uploading to " + target + " …");
        return upload(target, token, project, dist, jarName, descriptorName, shaName);
    }

    // ------------------------------------------------------------------
    // native upload
    // ------------------------------------------------------------------

    private int upload(String target, String token, MjProject project,
                       Path dist, String jarName, String descriptorName, String shaName) {
        String origin = target;
        String host = target.startsWith("gh:")
                ? ArtifactLocations.githubHost()
                : ArtifactLocations.giteeHost();
        String owner = origin.substring(origin.indexOf(':') + 1, origin.indexOf('/'));
        String repo = origin.substring(origin.indexOf('/') + 1);
        String version = project.version();
        String tag = "v" + version;

        List<Path> files = List.of(
                dist.resolve(project.name() + "-" + version + ".jar"),
                dist.resolve(project.name() + "-" + version + ".mj.toml"),
                dist.resolve(project.name() + "-" + version + ".sha256"));

        String releaseId;
        try {
            releaseId = createRelease(host, origin, owner, repo, tag, token);
        } catch (IOException | InterruptedException e) {
            System.err.println("mj: cannot create the release: " + e.getMessage());
            return 1;
        }

        for (Path file : files) {
            try {
                uploadFile(host, origin, owner, repo, releaseId, file, token);
            } catch (IOException | InterruptedException e) {
                System.err.println("mj: upload failed for " + file.getFileName() + ": "
                        + e.getMessage());
                return 1;
            }
        }

        System.out.println("uploaded; share it with:");
        System.out.println("  mj add \"" + project.name() + "\" " + target + "@" + tag);

        String page = target.startsWith("gh:")
                ? ArtifactLocations.githubHost()
                : ArtifactLocations.giteeHost();
        System.out.println("  " + page + "/" + owner + "/" + repo + "/releases/tag/" + tag);
        return 0;
    }

    /** Creates a release (or reuses the existing one) and returns its platform id. */
    private String createRelease(String host, String origin, String owner, String repo,
                                 String tag, String token)
            throws IOException, InterruptedException {
        boolean isGh = origin.startsWith("gh:");
        String apiBase = isGh ? "https://api.github.com" : host + "/api/v5";
        String url = apiBase + "/repos/" + origin.substring(origin.indexOf(':') + 1)
                + "/releases";

        String body;
        if (isGh) {
            body = "{\"tag_name\":\"" + tag + "\",\"name\":\"" + tag
                    + "\",\"body\":\"mj release\"}";
        } else {
            body = "{\"tag_name\":\"" + tag + "\",\"name\":\"" + tag
                    + "\",\"body\":\"mj release\",\"access_token\":\"" + token
                    + "\",\"target_commitish\":\"master\"}";
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (isGh) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            var map = JsonUtils.parseObject(response.body()).toMap();
            return String.valueOf(map.get("id"));
        }
        // release might already exist — attach to it (one repo, many modules
        // of the same version share one release)
        if (response.statusCode() == 422 || response.statusCode() == 409) {
            String existing = findReleaseId(isGh, host, owner, repo, tag, token);
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
    private String findReleaseId(boolean isGh, String host, String owner, String repo,
                                 String tag, String token)
            throws IOException, InterruptedException {
        String url = (isGh ? "https://api.github.com" : host + "/api/v5")
                + "/repos/" + owner + "/" + repo + "/releases/tags/" + tag;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (isGh) {
            builder.header("Authorization", "Bearer " + token);
        }
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

    /** Uploads one file as a release attachment. */
    private void uploadFile(String host, String origin, String owner, String repo,
                            String releaseId, Path file, String token)
            throws IOException, InterruptedException {
        boolean isGh = origin.startsWith("gh:");
        byte[] bytes = Files.readAllBytes(file);
        String fileName = file.getFileName().toString();

        if (isGh) {
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
        } else {
            // Gitee uses multipart form upload
            String url = host + "/api/v5/repos/" + owner + "/" + repo + "/releases/"
                    + releaseId + "/attach_files";
            String boundary = "----mj" + System.currentTimeMillis();
            var body = new ByteArrayOutputStream();
            writeMultipartField(body, boundary, "access_token", token);
            writeFilePart(body, boundary, "file", fileName, bytes);
            body.write(("--" + boundary + "--\r\n").getBytes());

            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "?access_token="
                    + token))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 200) {
                throw new IOException("upload of " + fileName + " returned "
                        + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(200, response.body().length())));
            }
        }
        System.out.println("  uploaded " + fileName);
    }

    private void writeMultipartField(ByteArrayOutputStream out, String boundary,
                                     String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFilePart(ByteArrayOutputStream out, String boundary,
                               String fieldName, String fileName, byte[] bytes)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // descriptor
    // ------------------------------------------------------------------

    /** The descriptor is the project's own dependency facts, verbatim. */
    private static String descriptor(MjProject project) {
        StringBuilder out = new StringBuilder();
        out.append("[project]\n");
        out.append("name = \"").append(project.name()).append("\"\n");
        out.append("version = \"").append(project.version()).append("\"\n");
        // identity facts travel with the release: they are what `mj info`
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
                        .append(requirementValue(requirement)).append("\"\n");
            }
        }
        return out.toString();
    }

    /** TOML basic-string escaping for the identity facts (quotes, backslashes). */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requirementValue(Requirement requirement) {
        return switch (requirement.species()) {
            case CENTRAL -> requirement.version();
            case GH, GITEE -> requirement.origin() + "@" + requirement.tag();
            case URL -> requirement.url();
        };
    }


    /** Zips {@code classes} into {@code jarFile} with the JDK's jar tool. */
    private static int jar(Path classes, Path jarFile) {
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
}
