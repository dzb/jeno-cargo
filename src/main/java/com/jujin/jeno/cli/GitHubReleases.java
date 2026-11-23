package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitHub's release API, shared by {@code publish} (library jars) and
 * {@code release} (application installers). One place for the token, the
 * overridable API roots, the 422 detail handling and asset upload.
 */
final class GitHubReleases {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private GitHubReleases() {}

    static String apiBase() {
        return System.getProperty("jeno.github.api", "https://api.github.com");
    }

    static String uploadsBase() {
        return System.getProperty("jeno.github.uploads", "https://uploads.github.com");
    }

    /** The documented environment variable, or a property for tests/CI. */
    static String token() {
        String fromEnvironment = System.getenv("JENO_GITHUB_TOKEN");
        return fromEnvironment != null && !fromEnvironment.isBlank()
                ? fromEnvironment
                : System.getProperty("jeno.github.token");
    }

    /** Creates a release or attaches to the existing one; returns its id. */
    static String create(String origin, String owner, String repo, String tag, String token,
                         String body) throws IOException, InterruptedException {
        String url = apiBase() + "/repos/" + owner + "/" + repo + "/releases";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            return String.valueOf(JsonUtils.parseObject(response.body()).toMap().get("id"));
        }
        if (response.statusCode() == 422 || response.statusCode() == 409) {
            String existing = find(owner, repo, tag, token);
            if (existing != null) {
                System.out.println("release " + tag + " already exists on " + origin
                        + " — attaching to it");
                return existing;
            }
            String detail = apiDetail(response.body());
            throw new IOException("cannot create the release on " + origin
                    + " (" + response.statusCode() + "): "
                    + (detail.isBlank() ? "no release with tag " + tag + " exists and the "
                            + "platform rejected the request" : detail));
        }
        throw new IOException("cannot create the release on " + origin
                + " (" + response.statusCode() + "): " + apiDetail(response.body()));
    }

    /** The release id for a tag, or null. */
    static String find(String owner, String repo, String tag, String token)
            throws IOException, InterruptedException {
        String url = apiBase() + "/repos/" + owner + "/" + repo + "/releases/tags/" + tag;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET()
                .header("Authorization", "Bearer " + token).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        String body = response.body();
        try {
            var map = JsonUtils.parseObject(body).toMap();
            return map.get("id") == null ? null : String.valueOf(map.get("id"));
        } catch (RuntimeException notAnObject) {
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

    /** Uploads one asset; returns the download URL the API reported (or null). */
    static String upload(String owner, String repo, String releaseId, Path file, String token)
            throws IOException, InterruptedException {
        byte[] bytes = Files.readAllBytes(file);
        String fileName = file.getFileName().toString();
        String url = uploadsBase() + "/repos/" + owner + "/" + repo
                + "/releases/" + releaseId + "/assets?name=" + fileName;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IOException("upload of " + fileName + " returned "
                    + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(200, response.body().length())));
        }
        System.out.println("  uploaded " + fileName);
        try {
            Object link = JsonUtils.parseObject(response.body()).toMap()
                    .get("browser_download_url");
            return link == null ? null : String.valueOf(link);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** Fetches bytes from a URL (redirects followed); used for read-back checks. */
    static byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(url + " answered " + response.statusCode());
        }
        return response.body();
    }

    /** GitHub's own message plus every errors[].message, truncated to a line. */
    static String apiDetail(String body) {
        String message = null;
        List<String> details = new ArrayList<>();
        try {
            Map<String, Object> map = JsonUtils.parseObject(body).toMap();
            if (map.get("message") != null) {
                message = String.valueOf(map.get("message"));
            }
            if (map.get("errors") instanceof List<?> errors) {
                for (Object error : errors) {
                    Object text = error instanceof Map<?, ?> fields ? fields.get("message") : null;
                    if (text != null && !String.valueOf(text).isBlank()) {
                        details.add(String.valueOf(text));
                    }
                }
            }
        } catch (RuntimeException notJson) {
            // raw body fallback
        }
        if (message == null && details.isEmpty()) {
            return truncate(body);
        }
        StringBuilder out = new StringBuilder();
        if (message != null) {
            out.append(truncate(message));
        }
        for (String detail : details) {
            if (!out.isEmpty()) {
                out.append(" — ");
            }
            out.append(truncate(detail));
        }
        return out.toString();
    }

    private static String truncate(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() <= 200 ? stripped : stripped.substring(0, 200) + "…";
    }
}
