package com.jujin.mj.project;

import com.jujin.freeway.commons.toml.TomlUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads mj.toml into {@link MjProject} under SPEC.md §2's closed schema:
 * only the frozen sections and keys exist, every value type is checked, and
 * anything outside the subset — an unknown section, an unquoted version, a
 * malformed coordinate, a non-https repository — fails with the rule named.
 * A misspelled {@code [dep]} is an error, never a silently empty table.
 *
 * <p>Value shapes decide the species (see {@link Requirement}): a bare
 * version is Central (the key must be {@code group:artifact}), a
 * {@code gh:}/{@code gitee:} shorthand is an mj-native release (semver tag,
 * {@code v} optional), a {@code https://…jar} URL is a bare leaf. The same
 * extraction serves descriptor files via {@link DescriptorReader}.
 */
public final class ProjectReader {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern COORDINATE = Pattern.compile("[^:\\s]+:[^:\\s]+");
    private static final Pattern NATIVE_VALUE = Pattern.compile("(gh|gitee):([^/\\s]+)/([^@\\s]+)@(.+)");
    private static final Pattern TAG = Pattern.compile("[vV]?(\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?)");
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9_.-]+");

    private ProjectReader() {}

    public static MjProject parse(String text) {
        Map<String, Object> root = TomlUtils.parse(text);

        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                throw new ProjectException(entry.getKey()
                        + " must be a table — every mj.toml entry is a [section] (SPEC.md §2)");
            }
            switch (entry.getKey()) {
                case "project", "java", "deps", "repos", "limits", "overrides", "scripts", "build" -> { }
                default -> throw new ProjectException("unknown section [" + entry.getKey()
                        + "] — mj.toml is a closed schema (SPEC.md §2)");
            }
        }

        Map<String, Object> projectSection = section(root, "project");
        String name = requireString(projectSection, "project", "name");
        if (!NAME.matcher(name).matches()) {
            throw new ProjectException("[project].name '" + name + "' must match [a-z][a-z0-9-]*");
        }
        String version = requireString(projectSection, "project", "version");
        String main = projectSection.containsKey("main")
                ? requireString(projectSection, "project", "main") : null;

        Integer javaVersion = null;
        Map<String, Object> javaSection = optionalSection(root, "java");
        if (javaSection != null) {
            javaVersion = requireInteger(javaSection, "java", "version", 1, 100);
        }

        Map<String, String> repos = new LinkedHashMap<>();
        Map<String, Object> reposSection = optionalSection(root, "repos");
        if (reposSection != null) {
            for (Map.Entry<String, Object> entry : reposSection.entrySet()) {
                if (!NAME.matcher(entry.getKey()).matches()) {
                    throw new ProjectException("[repos]." + entry.getKey()
                            + " must match [a-z][a-z0-9-]*");
                }
                String baseUrl = requireString(reposSection, "repos", entry.getKey());
                boolean remote = baseUrl.startsWith("https://");
                boolean local = baseUrl.startsWith("local:");
                if (!remote && !local) {
                    throw new ProjectException("[repos]." + entry.getKey()
                            + " must be an https URL or a local: directory — repository "
                            + "selection belongs to mj.toml alone");
                }
                repos.put(entry.getKey(), baseUrl);
            }
        }

        List<Requirement> requirements = List.of();
        Set<String> urlIds = new LinkedHashSet<>();
        Map<String, Object> depsSection = optionalSection(root, "deps");
        if (depsSection != null) {
            requirements = extractDependencies(depsSection, repos, "mj.toml");
            for (Requirement requirement : requirements) {
                if (requirement.species() == Species.URL) {
                    urlIds.add(requirement.id());
                }
            }
        }

        Map<String, String> overrides = new LinkedHashMap<>();
        Map<String, Object> overridesSection = optionalSection(root, "overrides");
        if (overridesSection != null) {
            for (Map.Entry<String, Object> entry : overridesSection.entrySet()) {
                if (urlIds.contains(entry.getKey())) {
                    throw new ProjectException("[overrides]." + entry.getKey()
                            + " — a URL dependency has no version to override");
                }
                overrides.put(entry.getKey(), requireString(overridesSection, "overrides", entry.getKey()));
            }
        }

        Map<String, String> scripts = new LinkedHashMap<>();
        Map<String, Object> scriptsSection = optionalSection(root, "scripts");
        if (scriptsSection != null) {
            for (Map.Entry<String, Object> entry : scriptsSection.entrySet()) {
                scripts.put(entry.getKey(), requireString(scriptsSection, "scripts", entry.getKey()));
            }
        }

        int maxDepth = 2;
        Map<String, Object> limitsSection = optionalSection(root, "limits");
        if (limitsSection != null) {
            maxDepth = requireInteger(limitsSection, "limits", "max-depth", 1, 255);
        }

        List<String> buildParameters = new ArrayList<>();
        Map<String, Object> buildSection = optionalSection(root, "build");
        if (buildSection != null) {
            for (Map.Entry<String, Object> entry : buildSection.entrySet()) {
                if (!entry.getKey().equals("parameters")) {
                    throw new ProjectException("[build]." + entry.getKey()
                            + " — the only supported key is 'parameters' (a string array "
                            + "of additional javac flags)");
                }
                if (!(entry.getValue() instanceof List<?> flags)) {
                    throw new ProjectException("[build].parameters must be a string array");
                }
                for (Object flag : flags) {
                    if (!(flag instanceof String stringFlag) || stringFlag.isBlank()) {
                        throw new ProjectException("[build].parameters entries must be "
                                + "non-blank strings (javac flags)");
                    }
                    buildParameters.add(stringFlag);
                }
            }
        }

        return new MjProject(name, version, main, javaVersion, maxDepth, requirements,
                overrides, scripts, repos, buildParameters);
    }

    /**
     * Parses one dependency declaration the way {@code mj add} receives it —
     * the same shapes and rules as the {@code [deps]} table, without a file.
     */
    public static Requirement parseRequirement(String key, String value) {
        return requirement(key, value, null, "mj add");
    }

    // ------------------------------------------------------------------
    // [deps] — shared with the descriptor reader
    // ------------------------------------------------------------------

    /**
     * Extracts the requirements of one {@code [deps]} table. A nested table is
     * a named-repository group ({@code [deps.corp]}), which only exists in
     * mj.toml — descriptors pass {@code repos == null} and get an error
     * instead (SPEC.md §3.2).
     */
    static List<Requirement> extractDependencies(
            Map<String, Object> deps, Map<String, String> repos, String where) {

        List<Requirement> requirements = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : deps.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> group) {
                if (repos == null) {
                    throw new ProjectException("[" + where + "] [deps." + key
                            + "] — descriptors cannot declare repositories; rules live only "
                            + "in the consumer's mj.toml (SPEC.md §3.2)");
                }
                if (!repos.containsKey(key)) {
                    throw new ProjectException("[deps." + key + "] names repository '" + key
                            + "' which is not declared in [repos]");
                }
                for (Map.Entry<String, Object> grouped : asStringKeyed(group).entrySet()) {
                    add(requirements, ids, requirement(grouped.getKey(), grouped.getValue(),
                            key, where), where);
                }
                continue;
            }
            add(requirements, ids, requirement(key, entry.getValue(), null, where), where);
        }
        return List.copyOf(requirements);
    }

    private static void add(List<Requirement> out, Set<String> ids, Requirement requirement, String where) {
        if (!ids.add(requirement.id())) {
            throw new ProjectException("[" + where + "] dependency '" + requirement.id()
                    + "' is declared twice — an id may appear once across all [deps] sections");
        }
        out.add(requirement);
    }

    /** One {@code key = value} entry, classified by the value's shape. */
    static Requirement requirement(String key, Object value, String repository, String where) {
        if (key.isBlank() || key.contains(" ") || key.contains("\t")) {
            throw new ProjectException("[" + where + "] dependency name '" + key
                    + "' must not be blank or contain whitespace");
        }
        if (!(value instanceof String raw) || raw.isBlank()) {
            throw new ProjectException("[" + where + "] '" + key
                    + "' must be a quoted string (a version, gh:/gitee: shorthand, or jar URL)");
        }

        if (raw.startsWith("gh:") || raw.startsWith("gitee:")) {
            Matcher matcher = NATIVE_VALUE.matcher(raw);
            if (!matcher.matches()) {
                throw new ProjectException("[" + where + "] '" + key + " = \"" + raw
                        + "\"' must be in the form "
                        + raw.substring(0, raw.indexOf(':')) + ":owner/repo@tag");
            }
            String host = matcher.group(1);
            String owner = matcher.group(2);
            String repo = matcher.group(3);
            String tag = matcher.group(4);
            Matcher tagMatcher = TAG.matcher(tag);
            if (!tagMatcher.matches()) {
                throw new ProjectException("[" + where + "] '" + key + "' uses tag '" + tag
                        + "' — an mj-native tag is an optional v plus semver (v1.2.3, 1.2.3-rc1)");
            }
            if (!OWNER.matcher(owner).matches() || !OWNER.matcher(repo).matches()) {
                throw new ProjectException("[" + where + "] '" + key
                        + "' — owner and repo must match " + OWNER.pattern());
            }
            Species species = host.equals("gh") ? Species.GH : Species.GITEE;
            return new Requirement(key, species, null, null, tagMatcher.group(1),
                    null, null, owner, repo, tag);
        }

        if (raw.startsWith("https://")) {
            if (!raw.endsWith(".jar")) {
                throw new ProjectException("[" + where + "] '" + key
                        + "' — a URL dependency must end in .jar");
            }
            return new Requirement(key, Species.URL, null, null, null, null, raw, null, null, null);
        }

        // A bare value is a Central version; the key must be group:artifact.
        if (!COORDINATE.matcher(key).matches() || key.indexOf(':') != key.lastIndexOf(':')) {
            throw new ProjectException("[" + where + "] '" + key
                    + "' — a Central dependency's key must be 'group:artifact'");
        }
        requireConcreteVersion(key, raw, where);
        int colon = key.indexOf(':');
        return new Requirement(key, Species.CENTRAL, key.substring(0, colon),
                key.substring(colon + 1), raw, repository, null, null, null, null);
    }

    private static void requireConcreteVersion(String key, String version, String where) {
        if (version.contains("${")) {
            throw new ProjectException("[" + where + "] '" + key
                    + "' — mj.toml does not interpolate properties; versions are literal");
        }
        if (version.startsWith("[") || version.startsWith("(")) {
            throw new ProjectException("[" + where + "] '" + key
                    + "' — version ranges are not supported; mj pins exact versions");
        }
        if (version.endsWith("-SNAPSHOT")) {
            throw new ProjectException("[" + where + "] '" + key
                    + "' — snapshot versions are not supported");
        }
    }

    // ------------------------------------------------------------------
    // typed accessors
    // ------------------------------------------------------------------

    private static Map<String, Object> section(Map<String, Object> root, String name) {
        Map<String, Object> section = optionalSection(root, name);
        if (section == null) {
            throw new ProjectException("[" + name + "] is required");
        }
        return section;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalSection(Map<String, Object> root, String name) {
        Object value = root.get(name);
        return value == null ? null : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyed(Map<?, ?> group) {
        return (Map<String, Object>) group;
    }

    private static String requireString(Map<String, Object> section, String sectionName, String key) {
        Object value = section.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new ProjectException("[" + sectionName + "]." + key + " must be a non-blank string");
        }
        return string;
    }

    private static int requireInteger(
            Map<String, Object> section, String sectionName, String key, int min, int max) {
        Object value = section.get(key);
        if (!(value instanceof Long integer)) {
            throw new ProjectException("[" + sectionName + "]." + key + " must be an integer");
        }
        if (integer < min || integer > max) {
            throw new ProjectException("[" + sectionName + "]." + key + " must be between "
                    + min + " and " + max + ", got " + integer);
        }
        return integer.intValue();
    }
}
