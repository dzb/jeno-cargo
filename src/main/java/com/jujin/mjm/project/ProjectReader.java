package com.jujin.mjm.project;

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
 * Reads mjm.toml into {@link MjProject} under SPEC.md §2's closed schema:
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

    /**
     * The sections whose keys are fixed: SPEC.md §2.1 lists every field, so a
     * key outside the list is a typo, never an extension point. Sections that
     * are tables of user-chosen names ({@code [deps]}, {@code [repos]},
     * {@code [scripts]}, {@code [overrides]}) validate value shapes instead.
     */
    private static final Map<String, Set<String>> FIXED_KEYS = Map.of(
            "project", Set.of("name", "group", "version", "main", "summary", "homepage",
                    "license"),
            "java", Set.of("version"),
            "limits", Set.of("max-depth"),
            "build", Set.of("parameters", "processors"),
            "test", Set.of("include", "exclude"));

    private static final String SPEC_SECTION = "SPEC.md §2.1";

    private ProjectReader() {}

    public static MjProject parse(String text) {
        return parse(text, "mjm.toml", SPEC_SECTION);
    }

    /**
     * The same parse, labelled for error messages — {@code file} is the name
     * the user typed or the descriptor that was fetched, {@code spec} the
     * section of SPEC.md that decided the schema.
     */
    static MjProject parse(String text, String file, String spec) {
        Map<String, Object> root = TomlUtils.parse(text);
        SchemaSource source = SchemaSource.scan(text);

        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                throw new ProjectException(entry.getKey()
                        + " must be a table — every mjm.toml entry is a [section] (SPEC.md §2)");
            }
            switch (entry.getKey()) {
                case "project", "java", "deps", "dev-deps", "repos", "limits", "overrides",
                     "scripts", "build", "test" -> { }

                default -> throw new ProjectException(at(file, source.section(entry.getKey()))
                        + "unknown section [" + entry.getKey()
                        + "] — mjm.toml is a closed schema (SPEC.md §2)");
            }
        }

        // Fixed sections list every field in SPEC.md §2.1: an unknown key is a
        // misspelling that used to be dropped silently.
        for (Map.Entry<String, Set<String>> fixed : FIXED_KEYS.entrySet()) {
            Map<String, Object> fixedSection = optionalSection(root, fixed.getKey());
            if (fixedSection != null) {
                checkKeys(fixedSection, source, file, fixed.getKey(), fixed.getValue(), spec);
            }
        }

        Map<String, Object> projectSection = section(root, "project");
        String name = requireString(projectSection, "project", "name");
        if (!NAME.matcher(name).matches()) {
            throw new ProjectException("[project].name '" + name + "' must match [a-z][a-z0-9-]*");
        }
        String group = projectSection.containsKey("group")
                ? requireString(projectSection, "project", "group") : null;
        if (group != null && !group.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new ProjectException("[project].group '" + group
                    + "' must be a reverse-DNS id (letters, digits, dot, dash, underscore)");
        }
        String version = requireString(projectSection, "project", "version");
        String main = projectSection.containsKey("main")
                ? requireString(projectSection, "project", "main") : null;
        // identity facts — optional; they travel into the published descriptor
        String summary = projectSection.containsKey("summary")
                ? requireString(projectSection, "project", "summary") : null;
        String homepage = projectSection.containsKey("homepage")
                ? requireString(projectSection, "project", "homepage") : null;
        if (homepage != null && !homepage.startsWith("https://")) {
            throw new ProjectException("[project].homepage must be an https URL, got " + homepage);
        }
        String license = projectSection.containsKey("license")
                ? requireString(projectSection, "project", "license") : null;

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
                            + "selection belongs to mjm.toml alone");
                }
                repos.put(entry.getKey(), baseUrl);
            }
        }

        List<Requirement> requirements = List.of();
        Set<String> urlIds = new LinkedHashSet<>();
        Map<String, Object> depsSection = optionalSection(root, "deps");
        if (depsSection != null) {
            requirements = extractDependencies(depsSection, repos, "mjm.toml");
            for (Requirement requirement : requirements) {
                if (requirement.species() == Species.URL) {
                    urlIds.add(requirement.id());
                }
            }
        }

        // [dev-deps] — test-only requirements (Cargo's dev-dependencies). It
        // is a top-level section, not [deps.dev]: [deps.<name>] already means
        // "dependencies from repository <name>". Same declaration rules, and
        // an id may not appear on both sides — one declaration, one scope.
        List<Requirement> devRequirements = List.of();
        Map<String, Object> devDepsSection = optionalSection(root, "dev-deps");
        if (devDepsSection != null) {
            devRequirements = extractDependencies(devDepsSection, repos, "mjm.toml");
            Set<String> mainIds = new LinkedHashSet<>();
            for (Requirement requirement : requirements) {
                mainIds.add(requirement.id());
            }
            for (Requirement requirement : devRequirements) {
                if (mainIds.contains(requirement.id())) {
                    throw new ProjectException("dependency '" + requirement.id()
                            + "' is declared in both [deps] and [dev-deps] — pick one scope; "
                            + "a main dependency is already on the test classpath");
                }
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
        List<String> processors = new ArrayList<>();
        Map<String, Object> buildSection = optionalSection(root, "build");
        if (buildSection != null) {
            for (Map.Entry<String, Object> entry : buildSection.entrySet()) {
                switch (entry.getKey()) {
                    case "parameters" -> {
                        for (String flag : stringArray(entry.getValue(), "[build].parameters")) {
                            buildParameters.add(flag);
                        }
                    }
                    case "processors" -> {
                        for (String coordinate : stringArray(entry.getValue(),
                                "[build].processors")) {
                            processors.add(coordinate);
                        }
                    }
                    default -> throw new ProjectException("[build]." + entry.getKey()
                            + " — the supported keys are 'parameters' (javac flags) and "
                            + "'processors' (annotation-processor coordinates)");
                }
            }
        }

        // [build].processors entries are "group:artifact:version" coordinates —
        // the version is mandatory there because a processor is a build tool,
        // not a library whose version the consumer should choose
        List<Requirement> processorRequirements = new ArrayList<>();
        for (String coordinate : processors) {
            int first = coordinate.indexOf(':');
            int second = first < 0 ? -1 : coordinate.indexOf(':', first + 1);
            if (second < 0) {
                throw new ProjectException("[build].processors entry '" + coordinate
                        + "' must be group:artifact:version");
            }
            String processorId = coordinate.substring(0, second);
            String processorVersion = coordinate.substring(second + 1);
            processorRequirements.add(
                    requirement(processorId, processorVersion, null, "build.processors"));
        }

        return new MjProject(name, group, version, main, javaVersion, maxDepth, requirements,
                overrides, scripts, repos, buildParameters, devRequirements,
                summary, homepage, license, processors, processorRequirements);
    }

    /**
     * Parses one dependency declaration the way {@code mjm add} receives it —
     * the same shapes and rules as the {@code [deps]} table, without a file.
     */
    public static Requirement parseRequirement(String key, String value) {
        return requirement(key, value, null, "mjm add");
    }

    // ------------------------------------------------------------------
    // [deps] — shared with the descriptor reader
    // ------------------------------------------------------------------

    /**
     * Extracts the requirements of one {@code [deps]} table. A nested table is
     * a named-repository group ({@code [deps.corp]}), which only exists in
     * mjm.toml — descriptors pass {@code repos == null} and get an error
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
                            + "in the consumer's mjm.toml (SPEC.md §3.2)");
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
                    + "' — mjm.toml does not interpolate properties; versions are literal");
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

    /** A non-empty array of non-blank strings — {@code [build]} keys share this shape. */
    private static List<String> stringArray(Object value, String where) {
        if (!(value instanceof List<?> entries)) {
            throw new ProjectException(where + " must be a string array");
        }
        List<String> result = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof String text) || text.isBlank()) {
                throw new ProjectException(where + " entries must be non-blank strings");
            }
            result.add(text);
        }
        return result;
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

    // ------------------------------------------------------------------
    // closed-schema key checks — positions come from SchemaSource
    // ------------------------------------------------------------------

    /** Rejects every key outside one fixed section's list, naming the line. */
    static void checkKeys(Map<String, Object> section, SchemaSource source, String file,
                          String sectionName, Set<String> allowed, String spec) {
        for (String key : section.keySet()) {
            if (!allowed.contains(key)) {
                throw unknownKey(file, source, sectionName, key, allowed, spec);
            }
        }
    }

    private static ProjectException unknownKey(String file, SchemaSource source, String sectionName,
                                               String key, Set<String> allowed, String spec) {
        StringBuilder message = new StringBuilder(at(file, source.key(sectionName, key)));
        message.append('[').append(sectionName).append("]").append('.').append(key)
                .append(" is not a key — allowed: ")
                .append(String.join(", ", allowed.stream().sorted().toList()));
        String guess = nearest(key, allowed);
        if (guess != null) {
            message.append("; did you mean '").append(guess).append("'?");
        }
        message.append(" (").append(spec).append(')');
        return new ProjectException(message.toString());
    }

    /**
     * {@code file:line:column: } — the prefix every schema error carries. A key
     * the scan could not place falls back to the file name alone, so the
     * message never loses the rule it is naming.
     */
    static String at(String file, SchemaSource.Site site) {
        return site == null ? file + ": "
                : file + ":" + site.line() + ":" + site.column() + ": ";
    }

    /** The allowed key closest to a typo, when it is close enough to suggest. */
    private static String nearest(String key, Set<String> candidates) {
        String best = null;
        int bestDistance = 3;  // two edits or fewer is a typo, not a different name
        for (String candidate : candidates) {
            int distance = editDistance(key, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
