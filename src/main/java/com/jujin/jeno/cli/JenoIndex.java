package com.jujin.jeno.cli;

import com.jujin.freeway.commons.toml.TomlUtils;
import com.jujin.jeno.project.Repository;
import com.jujin.jeno.resolve.ArtifactLocations;
import com.jujin.jeno.resolve.Versions;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One {@code index.jeno.toml} beside a named jeno artifact:
 * {@code <base>/<name>/index.jeno.toml}. It lists the versions a named jeno
 * repository publishes for that artifact. It is discovery metadata only: the
 * artifact descriptor and the lockfile remain the source of truth.
 */
final class JenoIndex {

    static final String FILE = "index.jeno.toml";
    static final int SCHEMA_VERSION = 1;
    private static final java.util.regex.Pattern ARTIFACT_NAME =
            java.util.regex.Pattern.compile("[a-z][a-z0-9-]*");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(MetadataVersions.requestTimeout())
            .build();

    record Index(String name, List<String> versions) {
        Index {
            versions = List.copyOf(versions);
        }

        static Index empty(String name) {
            return new Index(name, List.of());
        }

        Index withVersion(String version) {
            if (!Versions.isNativeSemver(version)) {
                throw new IndexException("version '" + version
                        + "' is not jeno-native semver and cannot enter " + FILE);
            }
            if (versions.contains(version)) {
                return this;
            }
            List<String> merged = new ArrayList<>(versions);
            merged.add(version);
            merged.sort(Versions::compare);
            return new Index(name, merged);
        }
    }

    /** The index does not exist yet; discovery can degrade to “not checked”. */
    static final class IndexUnavailable extends IndexException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        IndexUnavailable(String message) {
            super(message);
        }
    }

    /** The index exists but is malformed or unreadable. */
    static class IndexException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        IndexException(String message) {
            super(message);
        }

        IndexException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private JenoIndex() {}

    /** Read an index, or throw {@link IndexUnavailable} when it is absent. */
    static Index read(Repository repository, String artifactName) {
        String text = fetch(repository, artifactName);
        if (text == null) {
            throw new IndexUnavailable("no " + FILE + " at " + indexLocation(repository, artifactName));
        }
        return parse(text, artifactName, indexLocation(repository, artifactName));
    }

    /** Read an index, treating absence as an empty index. */
    static Index readOrEmpty(Repository repository, String artifactName) {
        String text = fetch(repository, artifactName);
        return text == null ? Index.empty(artifactName)
                : parse(text, artifactName, indexLocation(repository, artifactName));
    }

    /** Add {@code version} to the artifact's index and write it back. */
    static Index update(Repository repository, String artifactName, String version) {
        Index updated = readOrEmpty(repository, artifactName).withVersion(version);
        write(repository, updated);
        return updated;
    }

    static Index parse(String text, String expectedName, String source) {
        Map<String, Object> root;
        try {
            root = TomlUtils.parse(text);
        } catch (RuntimeException e) {
            throw new IndexException("cannot parse " + source + " — " + e.getMessage(), e);
        }
        Set<String> allowed = Set.of("schemaVersion", "name", "versions");
        for (String key : root.keySet()) {
            if (!allowed.contains(key)) {
                throw new IndexException(source + ": unknown key '" + key
                        + "' — allowed: schemaVersion, name, versions");
            }
        }
        Object schema = root.get("schemaVersion");
        if (!(schema instanceof Long schemaVersion) || schemaVersion != SCHEMA_VERSION) {
            throw new IndexException(source + ": schemaVersion must be integer "
                    + SCHEMA_VERSION);
        }
        Object nameValue = root.get("name");
        if (!(nameValue instanceof String name) || name.isBlank()) {
            throw new IndexException(source + ": name must be a non-blank string");
        }
        if (!name.equals(expectedName)) {
            throw new IndexException(source + ": index names '" + name
                    + "' but was read for '" + expectedName + "'");
        }
        Object versionsValue = root.get("versions");
        if (!(versionsValue instanceof List<?> rawVersions)) {
            throw new IndexException(source + ": versions must be a string array");
        }
        List<String> versions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object raw : rawVersions) {
            if (!(raw instanceof String candidate) || candidate.isBlank()) {
                throw new IndexException(source + ": versions entries must be non-blank strings");
            }
            if (!Versions.isNativeSemver(candidate)) {
                throw new IndexException(source + ": version '" + candidate
                        + "' is not jeno-native semver");
            }
            if (!seen.add(candidate)) {
                throw new IndexException(source + ": version '" + candidate
                        + "' appears twice");
            }
            versions.add(candidate);
        }
        if (versions.isEmpty()) {
            throw new IndexException(source + ": versions must not be empty");
        }
        versions.sort(Versions::compare);
        return new Index(name, versions);
    }

    static String render(Index index) {
        StringBuilder out = new StringBuilder();
        out.append("schemaVersion = ").append(SCHEMA_VERSION).append('\n');
        out.append("name = ").append(string(index.name())).append('\n');
        out.append("versions = [");
        for (int i = 0; i < index.versions().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(string(index.versions().get(i)));
        }
        out.append("]\n");
        return out.toString();
    }

    private static String fetch(Repository repository, String artifactName) {
        validateArtifactName(artifactName);
        if (repository.layout() != Repository.Layout.JENO) {
            throw new IndexException("index.jeno.toml only exists for jeno-layout repositories");
        }
        if (repository.isLocal()) {
            Path path = indexPath(repository, artifactName);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new IndexException("cannot read " + path + " — " + e.getMessage(), e);
            }
        }
        String url = indexUrl(repository, artifactName);
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(MetadataVersions.requestTimeout())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IndexException(url + " answered " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IndexException("cannot fetch " + url + " — " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IndexException("interrupted fetching " + url, e);
        }
    }

    private static void write(Repository repository, Index index) {
        validateArtifactName(index.name());
        byte[] bytes = render(index).getBytes(StandardCharsets.UTF_8);
        if (repository.isLocal()) {
            Path path = indexPath(repository, index.name());
            try {
                Files.createDirectories(path.getParent());
                Path temp = Files.createTempFile(path.getParent(), ".index-", ".tmp");
                try {
                    Files.write(temp, bytes);
                    try {
                        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
                return;
            } catch (IOException e) {
                throw new IndexException("cannot write " + path + " — " + e.getMessage(), e);
            }
        }
        String url = indexUrl(repository, index.name());
        try {
            JenoUploads.put(url, "application/toml; charset=utf-8", bytes);
            byte[] readBack = JenoUploads.get(url);
            if (!java.util.Arrays.equals(bytes, readBack)) {
                throw new IndexException(url + " read-back returned different bytes");
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IndexException("cannot write " + url + " — " + e.getMessage(), e);
        }
    }

    private static String indexUrl(Repository repository, String artifactName) {
        return repository.networkBase() + artifactName + "/" + FILE;
    }

    private static void validateArtifactName(String artifactName) {
        if (artifactName == null || !ARTIFACT_NAME.matcher(artifactName).matches()) {
            throw new IndexException("artifact name '" + artifactName
                    + "' must match [a-z][a-z0-9-]*");
        }
    }

    private static Path indexPath(Repository repository, String artifactName) {
        return repository.directory(ArtifactLocations.projectRoot())
                .resolve(artifactName).resolve(FILE);
    }

    private static String indexLocation(Repository repository, String artifactName) {
        return repository.isLocal() ? indexPath(repository, artifactName).toString()
                : indexUrl(repository, artifactName);
    }

    private static String string(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
