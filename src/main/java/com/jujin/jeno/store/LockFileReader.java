package com.jujin.jeno.store;

import com.jujin.freeway.commons.toml.TomlUtils;
import com.jujin.jeno.cli.LegacyNames;
import com.jujin.jeno.project.Repository;
import com.jujin.jeno.resolve.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads jeno.lock back into typed rows (SPEC.md §4). The schema is closed like
 * every jeno file: unknown keys in a row, a missing digest, or a depth below
 * one are errors — a hand-edited lock is a bug to surface, not a hint.
 */
public final class LockFileReader {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION_MARKER =
            Pattern.compile("^# jeno-lockfile-version:\\s*(\\d+)\\s*$");

    private LockFileReader() {}

    public static List<LockedArtifact> read(String text) {
        int lockVersion = lockfileVersion(text);
        Map<String, Object> root = TomlUtils.parse(text);
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!entry.getKey().equals("artifacts")) {
                throw new StoreException("jeno.lock: unknown section [" + entry.getKey()
                        + "] — the lockfile holds [[artifacts]] rows only");
            }
        }
        Object artifacts = root.get("artifacts");
        if (artifacts == null) {
            // A no-dependency project serializes a lock with no rows — the
            // array of tables cannot be written empty, absence is empty.
            return List.of();
        }
        if (!(artifacts instanceof List<?> rows)) {
            throw new StoreException("jeno.lock: [artifacts] is not an array of tables");
        }
        List<LockedArtifact> result = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (Object rowObject : rows) {
            if (!(rowObject instanceof Map<?, ?> row)) {
                throw new StoreException("jeno.lock: an [[artifacts]] row is not a table");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) row;
            for (String key : fields.keySet()) {
                if (!Set.of("id", "version", "source", "url", "sha256", "meta-sha256", "depth",
                        "scope", "api").contains(key)) {
                    throw new StoreException("jeno.lock: unknown field '" + key
                            + "' in the row for '" + fields.get("id") + "'");
                }
            }
            String rawSource = string(fields, "source");
            if (rawSource.startsWith(LegacyNames.REPOSITORY_PREFIX)) {
                throw new LegacyNames.LegacyException(LegacyNames.lockSource(
                        string(fields, "id"), rawSource));
            }
            if (rawSource.startsWith("gitee:")) {
                // every reader of a lock gets the same sentence: build, info, test,
                // outdated — the species is gone, the row has to be re-declared
                throw new StoreException("this jeno.lock pins gitee: artifacts, and the "
                        + "gitee: species was removed (its release assets need a token to "
                        + "read) — give '" + fields.get("id") + "' a release jar URL or a "
                        + "[repos] entry in jeno.toml, then run jeno lock");
            }
            String source = normalizedSource(rawSource, lockVersion, string(fields, "id"));
            LockedArtifact artifact = new LockedArtifact(
                    string(fields, "id"), string(fields, "version"), source,
                    string(fields, "url"), string(fields, "sha256"),
                    fields.containsKey("meta-sha256") ? string(fields, "meta-sha256") : null,
                    depth(fields), scope(fields), api(fields));
            if (!HEX64.matcher(artifact.sha256()).matches()) {
                throw new StoreException("jeno.lock: sha256 for '" + artifact.id()
                        + "' is not 64 hex characters");
            }
            if (artifact.metaSha256() != null
                    && !HEX64.matcher(artifact.metaSha256()).matches()) {
                throw new StoreException("jeno.lock: meta-sha256 for '" + artifact.id()
                        + "' is not 64 hex characters");
            }
            if (!ids.add(artifact.id())) {
                throw new StoreException("jeno.lock: '" + artifact.id()
                        + "' appears twice — one id, one version");
            }
            result.add(artifact);
        }
        return List.copyOf(result);
    }

    private static boolean api(Map<String, Object> fields) {
        Object value = fields.get("api");
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw new StoreException("jeno.lock: api for '" + fields.get("id")
                    + "' must be true or false");
        }
        return flag;
    }

    /**
     * The format marker is a comment, not a TOML key: older jeno readers
     * ignore it, while this reader can refuse a future format instead of
     * guessing. A lock written before the marker existed is version 1.
     */
    private static int lockfileVersion(String text) {
        boolean sawArtifacts = false;
        boolean sawVersion = false;
        int foundVersion = 1;
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.equals("[[artifacts]]")) {
                sawArtifacts = true;
                continue;
            }
            var matcher = VERSION_MARKER.matcher(stripped);
            if (!matcher.matches()) {
                continue;
            }
            if (sawArtifacts) {
                throw new StoreException("jeno.lock: lockfile version marker must precede "
                        + "[[artifacts]]");
            }
            if (sawVersion) {
                throw new StoreException("jeno.lock: duplicate lockfile version marker");
            }
            sawVersion = true;
            int version;
            try {
                version = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                throw new StoreException("jeno.lock: invalid lockfile version marker");
            }
            if (version < 1) {
                throw new StoreException("jeno.lock: lockfile version must be >= 1");
            }
            if (version > com.jujin.jeno.resolve.LockFile.VERSION) {
                throw new StoreException("jeno.lock: lockfile version " + version
                        + " is newer than this jeno supports ("
                        + com.jujin.jeno.resolve.LockFile.VERSION
                        + ") — upgrade jeno before using this lock");
            }
            foundVersion = version;
        }
        return foundVersion;
    }

    /**
     * Keep the in-memory canonical source spelling stable for every command.
     * v3's jeno-default grammar is normalized back to the internal
     * {@code jeno:...} form. The lock file itself writes the v3 spelling, so
     * this translation is confined to the reader; obsolete lock versions are
     * rejected rather than interpreted with a second grammar.
     */
    private static String normalizedSource(String source, int lockVersion, String id) {
        if (lockVersion <= 2) {
            return source;
        }
        if (source.equals("central") || source.equals("url")
                || source.startsWith("gh:")) {
            return source;
        }
        try {
            return Repository.parseLockSource(source, lockVersion).declaration();
        } catch (IllegalArgumentException e) {
            throw new StoreException("jeno.lock: invalid source for '" + id + "': "
                    + e.getMessage() + " (lockfile v3 uses jeno layout by default; "
                    + "write maven:<URL> for a Maven repository)");
        }
    }

    private static String string(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new StoreException("jeno.lock: '" + key + "' must be a non-blank string");
        }
        return string;
    }

    private static int depth(Map<String, Object> fields) {
        Object value = fields.get("depth");
        if (!(value instanceof Long depth) || depth < 1) {
            throw new StoreException("jeno.lock: 'depth' must be an integer >= 1");
        }
        return depth.intValue();
    }

    /**
     * An absent {@code scope} is MAIN — every lockfile written before
     * {@code [deps-dev]} existed pins no scope and must read unchanged.
     */
    private static Scope scope(Map<String, Object> fields) {
        Object value = fields.get("scope");
        if (value == null) {
            return Scope.MAIN;
        }
        if (!(value instanceof String text)) {
            throw new StoreException("jeno.lock: 'scope' is absent (main), \"dev\" or "
                    + "\"processor\" — got " + value);
        }
        return switch (text) {
            case "dev" -> Scope.DEV;
            case "processor" -> Scope.PROCESSOR;
            default -> throw new StoreException("jeno.lock: 'scope' is absent (main), \"dev\" "
                    + "or \"processor\" — got \"" + text + "\"");
        };
    }
}
