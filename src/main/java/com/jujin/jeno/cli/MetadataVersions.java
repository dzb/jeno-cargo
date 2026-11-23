package com.jujin.jeno.cli;

import com.jujin.jeno.resolve.ArtifactLocations;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code maven-metadata.xml}'s version list, straight from the repository —
 * the one fact Maven's layout exposes that jeno's own descriptor formats do not
 * repeat.
 *
 * <p>Shared by {@code jeno add} (choosing a version to declare) and
 * {@code jeno outdated} (comparing what is pinned against what exists), so both
 * answer "which versions are there" the same way — including the HTTP/file
 * fetch itself ({@link #text}), so the repository's wire format is read by one
 * piece of code.
 */
final class MetadataVersions {

    /**
     * A repository that accepts the connection and then never answers would
     * otherwise hang the command forever. Ten seconds is enough for DNS and a
     * TCP handshake over any link that is going to work; thirty is generous for
     * a slow mirror to produce headers, and short enough that a user does not
     * sit staring. The request timeout is read per call so a test can prove the
     * bound in under a second instead of waiting out the real one.
     */
    private static final java.time.Duration CONNECT_TIMEOUT =
            java.time.Duration.ofSeconds(10);

    // HTTP/1.1 on purpose: version lists are small single documents, and HTTP/2
    // failed on the real network where HTTP/1.1 was stable on every attempt.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * The request timeout, clamped to 100 ms … 120 s. The property exists so a
     * test can prove the bound quickly; it must never turn the tool into a
     * patient waiter — 0 or a negative value would mean "no deadline" and a
     * huge one is a hang by another name, so out-of-range values are raised or
     * capped instead of trusted.
     */
    static java.time.Duration requestTimeout() {
        long millis = Long.getLong("jeno.http.request.timeout.ms", 30_000L);
        millis = Math.max(100L, Math.min(120_000L, millis));
        return java.time.Duration.ofMillis(millis);
    }

    private MetadataVersions() {}

    /**
     * Every version listed for a Central coordinate, in document order.
     * Throws with the URL when the metadata cannot be read.
     */
    static List<String> central(String coordinate) {
        int colon = coordinate.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("'" + coordinate
                    + "' must be group:artifact");
        }
        String group = coordinate.substring(0, colon);
        String artifactId = coordinate.substring(colon + 1);
        return from(ArtifactLocations.centralBase(), group, artifactId);
    }

    /** Every version listed under {@code base/group/path/artifact/maven-metadata.xml}. */
    static List<String> from(String base, String group, String artifactId) {
        String root = base.endsWith("/") ? base : base + "/";
        String url = root + group.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
        return parse(text(url), url);
    }

    /** The version list in a metadata document; public for tests. */
    static List<String> parse(String xml, String url) {
        List<String> versions = new ArrayList<>();
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            boolean inVersion = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    inVersion = "version".equals(reader.getLocalName());
                } else if (event == XMLStreamReader.CHARACTERS && inVersion) {
                    String text = reader.getText().trim();
                    if (!text.isEmpty()) {
                        versions.add(text);
                    }
                } else if (event == XMLStreamReader.END_ELEMENT) {
                    inVersion = false;
                }
            }
            reader.close();
            return versions;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read maven-metadata.xml from " + url, e);
        }
    }

    /** One GET (or {@code file:} read) — metadata here, the Central index in {@code jeno add}. */
    static String text(String url) {
        if (url.startsWith("file://")) {
            try {
                return Files.readString(Path.of(URI.create(url)));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + url, e);
            }
        }
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(requestTimeout())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(url + " answered " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("cannot fetch " + url + " — " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted fetching " + url, e);
        }
    }
}
