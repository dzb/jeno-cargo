package com.jujin.jeno.project;

import com.jujin.jeno.cli.LegacyNames;
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
 * Reads jeno.toml into {@link JenoProject} under SPEC.md §2's closed schema:
 * only the frozen sections and keys exist, every value type is checked, and
 * anything outside the subset — an unknown section, an unquoted version, a
 * malformed coordinate, a non-https repository — fails with the rule named.
 * A misspelled {@code [dep]} is an error, never a silently empty table.
 *
 * <p>Value shapes decide the species (see {@link Requirement}): a bare
 * version is Central (the key must be {@code group:artifact}), a
 * {@code gh:} shorthand is a GitHub release (semver tag,
 * {@code v} optional), a {@code https://…jar} URL is a bare leaf. The same
 * extraction serves descriptor files via {@link DescriptorReader}.
 */
public final class ProjectReader {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern COORDINATE = Pattern.compile("[^:\\s]+:[^:\\s]+");
    private static final Pattern NATIVE_VALUE = Pattern.compile("gh:([^/\\s]+)/([^@\\s]+)@(.+)");
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
            "test", Set.of("include", "exclude"),
            "toolchain", Set.of("jars"));

    private static final String SPEC_SECTION = "SPEC.md §2.1";

    private ProjectReader() {}

    public static JenoProject parse(String text) {
        return parse(text, "jeno.toml", SPEC_SECTION);
    }

    /**
     * The same parse, labelled for error messages — {@code file} is the name
     * the user typed or the descriptor that was fetched, {@code spec} the
     * section of SPEC.md that decided the schema.
     */
    static JenoProject parse(String text, String file, String spec) {
        Map<String, Object> root = TomlUtils.parse(text);
        SchemaSource source = SchemaSource.scan(text);

        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                throw new ProjectException(entry.getKey()
                        + " must be a table — every jeno.toml entry is a [section] (SPEC.md §2)");
            }
            switch (entry.getKey()) {
                case "project", "java", "deps", "deps-dev", "deps-api", "repos", "limits",
                     "overrides", "scripts", "build", "test", "toolchain" -> { }

                case "dev-deps" -> throw new ProjectException(at(file,
                        source.section(entry.getKey()))
                        + "[dev-deps] was renamed to [deps-dev] — dependencies come first, "
                        + "the qualifier after; update this header and every [dev-deps.<repo>] "
                        + "group");

                default -> throw new ProjectException(at(file, source.section(entry.getKey()))
                        + "unknown section [" + entry.getKey()
                        + "] — jeno.toml is a closed schema (SPEC.md §2)");
            }
        }

        // [deps-api] is a marker table, not a second place to declare a
        // dependency. Keeping the declarations first makes that origin clear
        // and lets add/remove maintain one source of truth.
        if (root.containsKey("deps-api")) {
            SchemaSource.Site deps = source.section("deps");
            SchemaSource.Site api = source.section("deps-api");
            if (deps == null || api == null || api.line() <= deps.line()) {
                throw new ProjectException(at(file, api)
                        + "[deps] must be declared before [deps-api] — [deps] is the "
                        + "declaration table; [deps-api] only marks exported API deps");
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
        // SPEC.md §2.1: [java].version is optional — absent means "no target",
        // so an empty [java] section must not fail.
        if (javaSection != null && javaSection.containsKey("version")) {
            javaVersion = requireInteger(javaSection, "java", "version", 1, 100);
        }

        Map<String, Repository> repos = new LinkedHashMap<>();
        Map<String, Object> reposSection = optionalSection(root, "repos");
        if (reposSection != null) {
            for (Map.Entry<String, Object> entry : reposSection.entrySet()) {
                if (!NAME.matcher(entry.getKey()).matches()) {
                    throw new ProjectException("[repos]." + entry.getKey()
                            + " must match [a-z][a-z0-9-]*");
                }
                // gh: is a species prefix, matched before any repository lookup.
                // Letting [repos] take that name would make "gh:owner/repo@v1"
                // silently mean a repository named gh — a dependency that can
                // never resolve as itself.
                if (entry.getKey().equals("gh") || entry.getKey().equals("gitee")) {
                    throw new ProjectException(at(file, source.key("repos", entry.getKey()))
                            + "[repos]." + entry.getKey() + " is reserved for the "
                            + entry.getKey() + ": prefix — pick another name");
                }
                repos.put(entry.getKey(), repository(entry.getKey(), entry.getValue(),
                        reposSection, source, file));
            }
        }

        List<Requirement> requirements = List.of();
        Set<String> urlIds = new LinkedHashSet<>();
        Map<String, Object> depsSection = optionalSection(root, "deps");
        if (depsSection != null) {
            requirements = extractDependencies(depsSection, repos, "jeno.toml");
            for (Requirement requirement : requirements) {
                if (requirement.species() == Species.URL) {
                    urlIds.add(requirement.id());
                }
            }
        }

        Set<String> apiRequirements = Set.of();
        Map<String, Object> apiDepsSection = optionalSection(root, "deps-api");
        if (apiDepsSection != null) {
            apiRequirements = apiRequirementIds(apiDepsSection, requirements, repos,
                    "jeno.toml", null);
        }

        // [deps-dev] — test-only requirements. The prefix is the dependency
        // category and the suffix is its role: deps, deps-dev, deps-api.
        // Same declaration rules as [deps], and an id may not appear on both
        // sides — one declaration, one scope.
        List<Requirement> devRequirements = List.of();
        Map<String, Object> devDepsSection = optionalSection(root, "deps-dev");
        if (devDepsSection != null) {
            devRequirements = extractDependencies(devDepsSection, repos, "jeno.toml",
                    null, "deps-dev");
            Set<String> mainIds = new LinkedHashSet<>();
            for (Requirement requirement : requirements) {
                mainIds.add(requirement.id());
            }
            for (Requirement requirement : devRequirements) {
                if (mainIds.contains(requirement.id())) {
                    throw new ProjectException("dependency '" + requirement.id()
                            + "' is declared in both [deps] and [deps-dev] — pick one scope; "
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
        // SPEC.md §2.1: max-depth is optional, default 2 — an empty [limits] is
        // legal, only a declared-and-wrong value fails.
        if (limitsSection != null && limitsSection.containsKey("max-depth")) {
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
                    requirement(processorId, processorVersion, null, "build.processors",
                            Map.of(), false));
        }

        // [test].include / [test].exclude: typed and closed here, so TestCommand
        // consumes validated patterns instead of re-reading (and swallowing) the file
        List<String> testInclude = List.of();
        List<String> testExclude = List.of();
        Map<String, Object> testSection = optionalSection(root, "test");
        if (testSection != null) {
            testInclude = stringArrayAt(testSection, source, file, "test", "include");
            testExclude = stringArrayAt(testSection, source, file, "test", "exclude");
        }

        // [toolchain].jars: packaging metadata, not another dependency group.
        // The ids must already be in the locked closure; BundleCommand rejects
        // a stale list rather than silently shipping the wrong split.
        List<String> toolchain = new ArrayList<>();
        Map<String, Object> toolchainSection = optionalSection(root, "toolchain");
        if (toolchainSection != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String id : stringArrayAt(toolchainSection, source, file, "toolchain", "jars")) {
                if (!seen.add(id)) {
                    throw new ProjectException("[toolchain].jars lists '" + id
                            + "' twice — each toolchain artifact is named once");
                }
                toolchain.add(id);
            }
        }

        return new JenoProject(name, group, version, main, javaVersion, maxDepth, requirements,
                apiRequirements, overrides, scripts, repos, buildParameters, devRequirements,
                summary, homepage, license, processors, processorRequirements,
                testInclude, testExclude, toolchain);
    }

    /**
     * Parses a {@code [deps-api]} marker table. Every entry must name an
     * existing {@code [deps]} declaration and carry the exact same value; the
     * table is an API-visibility annotation, never a second declaration.
     */
    static Set<String> apiRequirementIds(Map<String, Object> apiSection,
                                         List<Requirement> base,
                                         Map<String, Repository> repos,
                                         String where,
                                         String sibling) {
        for (Map.Entry<String, Object> entry : apiSection.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?>) {
                throw new ProjectException("[deps-api]." + entry.getKey()
                        + " must be a direct id = value marker; nested repository groups "
                        + "belong in [deps]");
            }
        }
        List<Requirement> marked = extractDependencies(apiSection, repos, where, sibling);
        Map<String, Requirement> declared = new LinkedHashMap<>();
        for (Requirement requirement : base) {
            declared.put(requirement.id(), requirement);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Requirement requirement : marked) {
            Requirement original = declared.get(requirement.id());
            if (original == null) {
                throw new ProjectException("[deps-api]." + requirement.id()
                        + " must first be declared in [deps] — [deps] is the declaration "
                        + "table; [deps-api] only marks exported API deps");
            }
            if (!original.equals(requirement)) {
                throw new ProjectException("[deps-api]." + requirement.id()
                        + " must repeat the [deps] declaration exactly; [deps-api] is a "
                        + "marker, not a second version/source");
            }
            ids.add(requirement.id());
        }
        return Set.copyOf(ids);
    }

    /**
     * Parses one dependency declaration the way {@code jeno add} receives it —
     * the same shapes and rules as the {@code [deps]} table, without a file.
     */
    public static Requirement parseRequirement(String key, String value) {
        // add validates the shape here; whether the named repository is declared
        // is checked when the edited manifest is parsed for real
        return requirement(key, value, null, "jeno add", Map.of(), false);
    }

    // ------------------------------------------------------------------
    // [deps] — shared with the descriptor reader
    // ------------------------------------------------------------------

    /**
     * Extracts the requirements of one {@code [deps]} table. A nested table is
     * a named-repository group ({@code [deps.corp]}), which only exists in
     * jeno.toml — descriptors pass {@code repos == null} and get an error
     * instead (SPEC.md §3.2).
     */
    static List<Requirement> extractDependencies(
            Map<String, Object> deps, Map<String, Repository> repos, String where) {
        return extractDependencies(deps, repos, where, null, "deps");
    }

    /** The full form: {@code sibling} is set when the file came from an jeno: repository. */
    static List<Requirement> extractDependencies(
            Map<String, Object> deps, Map<String, Repository> repos, String where, String sibling) {
        return extractDependencies(deps, repos, where, sibling, "deps");
    }

    /** The same extraction under a named dependency root ({@code deps-dev}). */
    static List<Requirement> extractDependencies(
            Map<String, Object> deps, Map<String, Repository> repos, String where,
            String sibling, String dependencySection) {

        List<Requirement> requirements = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : deps.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> group) {
                if (repos == null) {
                    throw new ProjectException("[" + where + "] [" + dependencySection + "."
                            + key + "] — descriptors cannot declare repositories; rules live "
                            + "only in the consumer's jeno.toml (SPEC.md §3.2)");
                }
                if (!repos.containsKey(key)) {
                    throw new ProjectException("[" + dependencySection + "." + key
                            + "] names repository '" + key + "' which is not declared in [repos]");
                }
                for (Map.Entry<String, Object> grouped : asStringKeyed(group).entrySet()) {
                    add(requirements, ids, requirement(grouped.getKey(), grouped.getValue(),
                            key, where, repos, true, sibling), where, dependencySection);
                }
                continue;
            }
            add(requirements, ids, requirement(key, entry.getValue(), null, where, repos, true,
                    sibling), where, dependencySection);
        }
        return List.copyOf(requirements);
    }

    private static void add(List<Requirement> out, Set<String> ids, Requirement requirement,
                            String where, String dependencySection) {
        if (!ids.add(requirement.id())) {
            throw new ProjectException("[" + where + "] dependency '" + requirement.id()
                    + "' is declared twice — an id may appear once across all ["
                    + dependencySection + "] sections");
        }
        out.add(requirement);
    }

    /**
     * One {@code [repos]} entry: the explicit table ({@code type}/{@code url}) or
     * the legacy string. Both produce the same {@link Repository}; this is the
     * only place the two shapes are told apart.
     */
    private static Repository repository(String name, Object value,
                                         Map<String, Object> reposSection,
                                         SchemaSource source, String file) {
        if (value instanceof String legacy) {
            try {
                return Repository.parse(name, legacy);
            } catch (IllegalArgumentException e) {
                throw new ProjectException(at(file, source.key("repos", name))
                        + "[repos]." + name + " = \"" + legacy + "\" — " + e.getMessage());
            }
        }
        if (!(value instanceof Map<?, ?> table)) {
            throw new ProjectException(at(file, source.key("repos", name))
                    + "[repos]." + name + " must be a table with type/url, or the legacy string");
        }
        for (Object key : table.keySet()) {
            if (!key.equals("type") && !key.equals("url")) {
                throw new ProjectException(at(file, source.key("repos." + name,
                        String.valueOf(key)))
                        + "[repos]." + name + "." + key
                        + " is not a key — allowed: type, url");
            }
        }
        Object typeValue = table.get("type");
        if (typeValue != null && !(typeValue instanceof String)) {
            throw new ProjectException(at(file, source.key("repos." + name, "type"))
                    + "[repos]." + name + ".type must be \"maven\" or \"jeno\"");
        }
        String type = typeValue == null ? "maven" : (String) typeValue;
        Object urlValue = table.get("url");
        if (!(urlValue instanceof String url) || url.isBlank()) {
            throw new ProjectException(at(file, source.section("repos." + name))
                    + "[repos]." + name + " needs a url = \"https://…\" or a directory");
        }
        try {
            return Repository.of(name, type, url);
        } catch (IllegalArgumentException e) {
            throw new ProjectException(at(file, source.key("repos." + name, "type"))
                    + "[repos]." + name + " — " + e.getMessage());
        }
    }

    /** One {@code key = value} entry, classified by the value's shape. */
    static Requirement requirement(String key, Object value, String repository, String where,
                                   Map<String, Repository> repos, boolean checkRepos) {
        return requirement(key, value, repository, where, repos, checkRepos, null);
    }

    /**
     * The full form. {@code sibling} is the {@code jeno:} repository a descriptor
     * was fetched from: a bare name there addresses another artifact in that same
     * repository — a descriptor cannot name repositories (they are rules), but it
     * can say "the sibling called gadget, version 0.9.0".
     */
    static Requirement requirement(String key, Object value, String repository, String where,
                                   Map<String, Repository> repos, boolean checkRepos, String sibling) {
        if (repository == null && sibling != null && value instanceof String raw
                && !raw.isBlank() && !raw.startsWith("gh:") && !raw.startsWith("https://")
                && !raw.contains(":") && !key.contains(":")) {
            return nativeInNamedRepository(key, raw, sibling, where);
        }
        // [deps.mine] where mine is an jeno: repository: the native layout, no group
        if (repository != null && repos != null
                && repos.get(repository) != null
                && repos.get(repository).layout() == Repository.Layout.JENO) {
            return nativeInNamedRepository(key, (String) value, repository, where);
        }
        if (key.isBlank() || key.contains(" ") || key.contains("\t")) {
            throw new ProjectException("[" + where + "] dependency name '" + key
                    + "' must not be blank or contain whitespace");
        }
        if (!(value instanceof String raw) || raw.isBlank()) {
            throw new ProjectException("[" + where + "] '" + key
                    + "' must be a quoted string (a version, gh: shorthand, or jar URL)");
        }

        if (raw.startsWith("gitee:")) {
            throw new ProjectException("[" + where + "] '" + key + " = \"" + raw
                    + "\"' — the gitee: species was removed (Gitee release assets need a "
                    + "token even to read). Use the release jar URL directly "
                    + "(\"https://gitee.com/<owner>/<repo>/releases/download/<tag>/<artifact>.jar\"), "
                    + "or publish to your own host and declare it in [repos]");
        }

        if (raw.startsWith("gh:")) {
            Matcher matcher = NATIVE_VALUE.matcher(raw);
            if (!matcher.matches()) {
                throw new ProjectException("[" + where + "] '" + key + " = \"" + raw
                        + "\"' must be in the form gh:owner/repo@tag");
            }
            String owner = matcher.group(1);
            String repo = matcher.group(2);
            String tag = matcher.group(3);
            Matcher tagMatcher = TAG.matcher(tag);
            if (!tagMatcher.matches()) {
                throw new ProjectException("[" + where + "] '" + key + "' uses tag '" + tag
                        + "' — a jeno-native tag is an optional v plus semver (v1.2.3, 1.2.3-rc1)");
            }
            if (!OWNER.matcher(owner).matches() || !OWNER.matcher(repo).matches()) {
                throw new ProjectException("[" + where + "] '" + key
                        + "' — owner and repo must match " + OWNER.pattern());
            }
            return new Requirement(key, Species.GH, null, null, tagMatcher.group(1),
                    null, null, owner, repo, tag);
        }

        if (raw.startsWith(LegacyNames.REPOSITORY_PREFIX)) {
            throw new ProjectException("[" + where + "] '" + key + " = \"" + raw
                    + "\"' — " + LegacyNames.repositoryPrefix(raw));
        }
        if (raw.startsWith("https://")) {
            if (!raw.endsWith(".jar")) {
                throw new ProjectException("[" + where + "] '" + key
                        + "' — a URL dependency must end in .jar");
            }
            return new Requirement(key, Species.URL, null, null, null, null, raw, null, null, null);
        }

        // "name:version" names a repository (SPEC.md §2.1, value shapes): the
        // colon-prefix has to look like a repository name and carry a version.
        // Anything else — "1.2.3", or a prefix that is not a repo name — stays a
        // Central version below.
        int namedColon = raw.indexOf(':');
        if (namedColon > 0) {
            String name = raw.substring(0, namedColon);
            String version = raw.substring(namedColon + 1).strip();
            if (NAME.matcher(name).matches() && !version.isEmpty()) {
                if (repos == null) {
                    throw new ProjectException("[" + where + "] '" + key + " = \"" + raw
                            + "\"' — descriptors cannot declare repositories; rules live only "
                            + "in the consumer's jeno.toml (SPEC.md §3.2)");
                }
                boolean namedJeno = repos != null && checkRepos
                        && repos.get(name) != null
                        && repos.get(name).layout() == Repository.Layout.JENO;
                if (namedJeno || (!checkRepos && !key.contains(":"))) {
                    // "name:version": a named jeno: repository, keyed by artifact name
                    return nativeInNamedRepository(key, version, name, where);
                }
                if (checkRepos && !repos.containsKey(name)) {
                    throw new ProjectException("[" + where + "] '" + key + " = \"" + raw
                            + "\"' names repository '" + name + "' which is not declared in "
                            + "[repos] — declared: " + (repos.isEmpty() ? "(none)"
                                    : String.join(", ", repos.keySet())));
                }
                if (version.contains(" ") || version.contains("\t")) {
                    throw new ProjectException("[" + where + "] '" + key
                            + "' — a version must not contain whitespace");
                }
                if (!COORDINATE.matcher(key).matches() || key.indexOf(':') != key.lastIndexOf(':')) {
                    throw new ProjectException("[" + where + "] '" + key
                            + "' — a repository dependency's key must be 'group:artifact'");
                }
                requireConcreteVersion(key, version, where);
                int keyColon = key.indexOf(':');
                return new Requirement(key, Species.CENTRAL, key.substring(0, keyColon),
                        key.substring(keyColon + 1), version, name, null, null, null, null);
            }
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

    /**
     * A dependency on a native release inside an {@code jeno:} repository: the key
     * is the artifact name (the layout has no group), the version is semver, and
     * metadata comes from the descriptor beside the jar — the same parse path as
     * gh:.
     */
    private static Requirement nativeInNamedRepository(String key, String version,
                                                       String repository, String where) {
        if (key.contains(":")) {
            throw new ProjectException("[" + where + "] '" + key + "' — a dependency from a "
                    + "jeno: repository is addressed by its artifact name (no group:artifact)");
        }
        if (!NAME.matcher(key).matches()) {
            throw new ProjectException("[" + where + "] dependency name '" + key
                    + "' must match [a-z][a-z0-9-]* for a jeno: repository");
        }
        String stripped = version == null ? "" : version.strip();
        Matcher tag = TAG.matcher(stripped);
        if (!tag.matches()) {
            throw new ProjectException("[" + where + "] '" + key + "' uses version '"
                    + stripped + "' — a jeno: repository holds semver releases (1.2.3, 1.2.3-rc1)");
        }
        return new Requirement(key, Species.NAMED, null, null, tag.group(1), repository,
                null, null, null, null);
    }

    private static void requireConcreteVersion(String key, String version, String where) {
        if (version.contains("${")) {
            throw new ProjectException("[" + where + "] '" + key
                    + "' — jeno.toml does not interpolate properties; versions are literal");
        }
        if (version.startsWith("[") || version.startsWith("(")) {
            throw new ProjectException("[" + where + "] '" + key
                    + "' — version ranges are not supported; jeno pins exact versions");
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

    /**
     * The same shape check, with the key's line and column on failure — used by
     * {@code [test]}, whose patterns used to be read a second time (and errors
     * swallowed) by {@code TestCommand}.
     */
    private static List<String> stringArrayAt(Map<String, Object> section, SchemaSource source,
                                              String file, String sectionName, String key) {
        Object value = section.get(key);
        if (value == null) {
            return List.of();
        }
        String where = "[" + sectionName + "]" + "." + key;
        SchemaSource.Site site = source.key(sectionName, key);
        if (!(value instanceof List<?> entries)) {
            throw new ProjectException(at(file, site) + where + " must be a string array");
        }
        List<String> result = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof String text) || text.isBlank()) {
                throw new ProjectException(at(file, site) + where
                        + " entries must be non-blank strings");
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
