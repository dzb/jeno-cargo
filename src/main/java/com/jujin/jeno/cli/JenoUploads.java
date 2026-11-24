package com.jujin.jeno.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.jujin.jeno.project.Repository;

import java.time.Duration;

/**
 * HTTP PUT/GET for jeno-layout repositories, shared by publish and release.
 *
 * <p>Uploads use PUT, not POST. The jeno layout gives every byte a final,
 * deterministic URL, and PUT makes that URL the resource itself: retrying an
 * interrupted publish is idempotent and the server does not have to invent a
 * name or return a Location. POST is the right verb for a server-assigned
 * upload endpoint or a multipart form, but that would require a host-specific
 * protocol and break jeno's "a repository is a URL" contract. A POST-only host
 * can expose a small PUT gateway if it wants to serve jeno repositories.
 */
final class JenoUploads {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private JenoUploads() {}

    /** One PUT with the generic binary content type. */
    static void put(String url, byte[] bytes) throws IOException, InterruptedException {
        put(url, "application/octet-stream", bytes);
    }

    /** One PUT, treating 200/201/204 as success; JENO_PUBLISH_TOKEN is optional. */
    static void put(String url, String contentType, byte[] bytes)
            throws IOException, InterruptedException {
        String token = System.getenv("JENO_PUBLISH_TOKEN");
        if (token != null && !token.isBlank() && isCleartextNonLoopback(url)
                && !"1".equals(System.getenv("JENO_ALLOW_INSECURE_TOKEN"))) {
            throw new IOException("refusing to send JENO_PUBLISH_TOKEN over plain HTTP ("
                    + url + ") — set JENO_ALLOW_INSECURE_TOKEN=1 only for a trusted LAN");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status != 200 && status != 201 && status != 204) {
            throw new IOException("server answered " + status
                    + (response.body().isBlank() ? "" : ": " + response.body().substring(
                            0, Math.min(200, response.body().length()))));
        }
    }

    /** Whether a URL would put the bearer token on an unencrypted LAN. */
    static boolean isCleartextNonLoopback(String url) {
        return url != null && url.startsWith("http://")
                && !Repository.isLoopbackHttp(url);
    }

    /** One GET; the release read-back check. */
    static byte[] get(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(url + " answered " + response.statusCode());
        }
        return response.body();
    }
}
