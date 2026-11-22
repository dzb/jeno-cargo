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
                int close = outsideQuotes(line, ']', 1);
                if (close < 0) {
                    continue;
                }
                currentSection = unquote(line.substring(1, close).strip());
                source.sections.putIfAbsent(currentSection, new Site(lineNumber, column));
                continue;
            }
            int equals = outsideQuotes(line, '=', 0);
            if (equals < 0) {
                continue;  // a string-array continuation
            }
            String rawKey = line.substring(0, equals).strip();
            String key = unquote(rawKey);
            if (key.equals(rawKey) && !key.matches("[A-Za-z0-9_-]+")) {
                continue;  // a continuation line that happens to hold '='
            }
            source.keys.computeIfAbsent(currentSection, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(key, new Site(lineNumber, column));
        }
        return source;
    }

    /**
     * The first {@code target} outside a basic string. A quoted key may contain
     * {@code =} ({@code "mi=an"}), and a quoted table name may contain
     * {@code ]} ({@code ["dep"]}) — both are legal TOML, and both used to eat
     * the separator and leave an error with no line or column.
     */
    private static int outsideQuotes(String line, char target, int from) {
        boolean quoted = false;
        for (int i = from; i < line.length(); i++) {
            char current = line.charAt(i);
            if (quoted && current == '\\') {
                i++;  // an escaped character cannot close the string
                continue;
            }
            if (current == '"') {
                quoted = !quoted;
                continue;
            }
            if (current == target && !quoted) {
                return i;
            }
        }
        return -1;
    }

    /** A basic string without its quotes and with the two escapes the subset allows. */
    private static String unquote(String token) {
        if (token.length() < 2 || !token.startsWith("\"") || !token.endsWith("\"")) {
            return token;
        }
        return token.substring(1, token.length() - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
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
