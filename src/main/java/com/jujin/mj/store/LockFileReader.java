package com.jujin.mj.store;

import com.jujin.freeway.commons.toml.TomlUtils;
import com.jujin.mj.resolve.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads mj.lock back into typed rows (SPEC.md §4). The schema is closed like
 * every mj file: unknown keys in a row, a missing digest, or a depth below
 * one are errors — a hand-edited lock is a bug to surface, not a hint.
 */
public final class LockFileReader {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private LockFileReader() {}

    public static List<LockedArtifact> read(String text) {
        Map<String, Object> root = TomlUtils.parse(text);
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!entry.getKey().equals("artifacts")) {
                throw new StoreException("mj.lock: unknown section [" + entry.getKey()
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
            throw new StoreException("mj.lock: [artifacts] is not an array of tables");
        }
        List<LockedArtifact> result = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (Object rowObject : rows) {
            if (!(rowObject instanceof Map<?, ?> row)) {
                throw new StoreException("mj.lock: an [[artifacts]] row is not a table");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) row;
            for (String key : fields.keySet()) {
                if (!Set.of("id", "version", "source", "url", "sha256", "meta-sha256", "depth",
                        "scope").contains(key)) {
                    throw new StoreException("mj.lock: unknown field '" + key
                            + "' in the row for '" + fields.get("id") + "'");
                }
            }
            LockedArtifact artifact = new LockedArtifact(
                    string(fields, "id"), string(fields, "version"), string(fields, "source"),
                    string(fields, "url"), string(fields, "sha256"),
                    fields.containsKey("meta-sha256") ? string(fields, "meta-sha256") : null,
                    depth(fields), scope(fields));
            if (!HEX64.matcher(artifact.sha256()).matches()) {
                throw new StoreException("mj.lock: sha256 for '" + artifact.id()
                        + "' is not 64 hex characters");
            }
            if (artifact.metaSha256() != null
                    && !HEX64.matcher(artifact.metaSha256()).matches()) {
                throw new StoreException("mj.lock: meta-sha256 for '" + artifact.id()
                        + "' is not 64 hex characters");
            }
            if (!ids.add(artifact.id())) {
                throw new StoreException("mj.lock: '" + artifact.id()
                        + "' appears twice — one id, one version");
            }
            result.add(artifact);
        }
        return List.copyOf(result);
    }

    private static String string(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new StoreException("mj.lock: '" + key + "' must be a non-blank string");
        }
        return string;
    }

    private static int depth(Map<String, Object> fields) {
        Object value = fields.get("depth");
        if (!(value instanceof Long depth) || depth < 1) {
            throw new StoreException("mj.lock: 'depth' must be an integer >= 1");
        }
        return depth.intValue();
    }

    /**
     * An absent {@code scope} is MAIN — every lockfile written before
     * {@code [dev-deps]} existed pins no scope and must read unchanged.
     */
    private static Scope scope(Map<String, Object> fields) {
        Object value = fields.get("scope");
        if (value == null) {
            return Scope.MAIN;
        }
        if (!(value instanceof String text) || !text.equals("dev")) {
            throw new StoreException("mj.lock: 'scope' is either absent (main) or the string "
                    + "\"dev\" — got " + value);
        }
        return Scope.DEV;
    }
}
