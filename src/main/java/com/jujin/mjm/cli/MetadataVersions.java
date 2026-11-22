package com.jujin.mjm.cli;

import com.jujin.mjm.resolve.ArtifactLocations;

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
 * the one fact Maven's layout exposes that mjm's own descriptor formats do not
 * repeat.
 *
 * <p>Shared by {@code mjm add} (choosing a version to declare) and
 * {@code mjm outdated} (comparing what is pinned against what exists), so both
 * answer "which versions are there" the same way.
 */
final class MetadataVersions {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
        String root = ArtifactLocations.baseUrl(base);
        String url = root + group.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
        return parse(fetch(url), url);
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

    private static String fetch(String url) {
        if (url.startsWith("file://")) {
            try {
                return Files.readString(Path.of(URI.create(url)));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + url, e);
            }
        }
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
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
