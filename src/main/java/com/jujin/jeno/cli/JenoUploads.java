package com.jujin.jeno.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP PUT/GET for mjm-layout repositories, shared by publish and release.
 *
 * <p>Uploads use PUT, not POST. The mjm layout gives every byte a final,
 * deterministic URL, and PUT makes that URL the resource itself: retrying an
 * interrupted publish is idempotent and the server does not have to invent a
 * name or return a Location. POST is the right verb for a server-assigned
 * upload endpoint or a multipart form, but that would require a host-specific
 * protocol and break mjm's "a repository is a URL" contract. A POST-only host
 * can expose a small PUT gateway if it wants to serve mjm repositories.
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

    /** One PUT, treating 200/201/204 as success; MJM_PUBLISH_TOKEN is optional. */
    static void put(String url, String contentType, byte[] bytes)
            throws IOException, InterruptedException {
        String token = System.getenv("MJM_PUBLISH_TOKEN");
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
