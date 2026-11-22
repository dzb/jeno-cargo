package com.jujin.mjm.project;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where each section header and key sits in a TOML source: 1-based line and
 * column. The TOML parser returns values only, so a schema error could name
 * the rule it broke but not the place to fix it; this is a second, tiny pass
 * over the same subset (SPEC.md §1.4) — table headers and {@code key = value}
 * lines — that remembers the coordinates.
 *
 * <p>A line that does not start with a key is skipped: continuation lines of a
 * string array, comments, blanks. A quoted key ({@code "group:artifact"}) is
 * unwrapped, so lookups use the same keys the parser produced.
 */
final class SchemaSource {

    /** A 1-based position in the source text. */
    record Site(int line, int column) {}

    private final Map<String, Site> sections = new LinkedHashMap<>();
    private final Map<String, Map<String, Site>> keys = new LinkedHashMap<>();

    static SchemaSource scan(String text) {
        SchemaSource source = new SchemaSource();
        String currentSection = "";
        int lineNumber = 0;
        for (String raw : text.split("\n", -1)) {
            lineNumber++;
            String line = raw.stripLeading();
            int column = raw.length() - line.length() + 1;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                int close = line.indexOf(']');
                if (close < 0) {
                    continue;
                }
                currentSection = line.substring(1, close).strip();
                source.sections.putIfAbsent(currentSection, new Site(lineNumber, column));
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;  // a string-array continuation
            }
            String key = line.substring(0, equals).strip();
            if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            } else if (!key.matches("[A-Za-z0-9_-]+")) {
                continue;  // a continuation line that happens to hold '='
            }
            source.keys.computeIfAbsent(currentSection, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(key, new Site(lineNumber, column));
        }
        return source;
    }

    /** The header line of a section, or null when it is absent. */
    Site section(String name) {
        return sections.get(name);
    }

    /** The line of one key inside a section, or null when the scan missed it. */
    Site key(String section, String key) {
        Map<String, Site> inSection = keys.get(section);
        return inSection == null ? null : inSection.get(key);
    }
}
