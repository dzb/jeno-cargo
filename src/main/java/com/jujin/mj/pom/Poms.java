package com.jujin.mj.pom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes Maven {@code pom.xml} documents into mj's own model. This is the
 * compatibility layer of DESIGN.md §5: it exists so mj can pull libraries
 * from Maven Central, not because Maven's semantics are a goal. What it
 * supports is exactly what decoding a Central artifact needs:
 *
 * <ul>
 *   <li><b>Parent chains</b> — versions live in parents, so a pom with a
 *       {@code <parent>} is resolved recursively; coordinates, properties,
 *       packaging and managed dependencies inherit; cycles fail.</li>
 *   <li><b>Property interpolation</b> — {@code ${...}} resolved to a fixpoint
 *       against the merged properties plus {@code project.*} /
 *       {@code project.parent.*} builtins ({@code pom.*} aliases included);
 *       the {@code ${revision}} CI-friendly version pattern works. An
 *       unresolvable property in a field mj needs is an error, not a silent
 *       literal.</li>
 *   <li><b>Dependency management</b> — own entries override the parent's;
 *       BOM imports ({@code <type>pom</type> <scope>import</scope>}) fill
 *       only the gaps left by own and inherited entries, first import
 *       winning; a managed entry supplies version, scope (when the dependency
 *       declares none) and exclusions (merged with the dependency's own).</li>
 *   <li><b>Transitive scopes</b> — the decoded dependency list holds exactly
 *       what another project inherits: {@code compile} and {@code runtime},
 *       non-optional. {@code test}/{@code provided}/{@code system} never
 *       propagate, so they are dropped here.</li>
 * </ul>
 *
 * <p>Everything outside the subset is rejected and named (DESIGN.md §5.2):
 * version ranges, snapshot versions, unsupported packaging and dependency
 * types, version mismatches between a pom and its coordinate. Poms never
 * point mj at other repositories — the {@code <repositories>} section is
 * ignored, repository selection belongs to mj.toml alone.
 */
public final class Poms {

    private Poms() {}

    /**
     * Decodes {@code group:artifact:version} into the effective model, pulling
     * parent poms and BOM imports through {@code source} as needed.
     */
    public static Pom decode(String groupId, String artifactId, String version,
                             String repositoryBase, PomSource source) {
        Objects.requireNonNull(source, "source");
        requireConcrete(groupId, artifactId, version, "the requested artifact");
        Effective effective = load(groupId, artifactId, version, repositoryBase, source,
                new LinkedHashSet<>());
        return new Pom(effective.groupId(), artifactId, effective.version(),
                effective.packaging(), effective.dependencies());
    }

    // ------------------------------------------------------------------
    // decoding
    // ------------------------------------------------------------------

    private static Effective load(
            String groupId, String artifactId, String version, String repositoryBase,
            PomSource source, Set<String> chain) {

        String self = key(groupId, artifactId, version);
        if (!chain.add(self)) {
            throw new PomException("circular parent chain reaching " + self + " via "
                    + String.join(" -> ", chain));
        }

        String xml;
        try {
            xml = source.pomXml(groupId, artifactId, version, repositoryBase);
        } catch (RuntimeException e) {
            throw new PomException("unable to fetch the pom for " + self, e);
        }
        if (xml == null) {
            throw new PomException("no pom for " + self + " was found on the source");
        }
        if (xml.isBlank()) {
            throw new PomException("pom for " + self + " is empty");
        }
        RawPom raw;
        try {
            raw = toRaw(PomXml.parse(xml));
        } catch (PomException e) {
            throw new PomException("cannot decode the pom for " + self + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new PomException("unable to read the pom for " + self, e);
        }

        Effective parent = null;
        String parentGroupId = null;
        String parentVersion = null;
        if (raw.parent() != null) {
            RawPom.Parent declared = raw.parent();
            requireConcrete(declared.groupId(), declared.artifactId(), declared.version(),
                    "the parent of " + self);
            parent = load(declared.groupId(), declared.artifactId(), declared.version(),
                    repositoryBase, source, chain);
            parentGroupId = declared.groupId();
            parentVersion = declared.version();
        }

        // Coordinates: own first, then the parent's — artifactId is never inherited.
        if (blank(raw.artifactId())) {
            throw new PomException("pom for " + self + " has no artifactId");
        }
        String effectiveGroupId = firstNonBlank(raw.groupId(),
                parentGroupId, groupId);
        String effectiveVersion = firstNonBlank(raw.version(), parentVersion, version);

        Map<String, String> properties = new LinkedHashMap<>(
                parent == null ? Map.of() : parent.properties());
        properties.putAll(raw.properties());
        properties.put("project.groupId", effectiveGroupId);
        properties.put("project.artifactId", artifactId);
        properties.put("project.version", effectiveVersion);
        if (parent != null) {
            properties.put("project.parent.groupId", parentGroupId);
            properties.put("project.parent.artifactId", raw.parent().artifactId());
            properties.put("project.parent.version", parentVersion);
        }
        for (String name : List.copyOf(properties.keySet())) {
            if (name.startsWith("project.")) {
                properties.putIfAbsent("pom." + name.substring("project.".length()),
                        properties.get(name));
            }
        }
        Map<String, String> resolved = resolveProperties(properties);

        // The pom must agree with the coordinate it was fetched under — this
        // is what catches relocations and mislabeled artifacts early.
        String interpolatedVersion = interpolate(effectiveVersion, resolved);
        requireConcrete(groupId, artifactId, interpolatedVersion, "the version of " + self);
        if (!interpolatedVersion.equals(version)) {
            throw new PomException("pom for " + self + " declares version '"
                    + interpolatedVersion + "' but was fetched as '" + version
                    + "' — relocations are not supported");
        }
        String interpolatedGroupId = interpolate(effectiveGroupId, resolved);
        if (!interpolatedGroupId.equals(groupId)) {
            throw new PomException("pom for " + self + " declares groupId '"
                    + interpolatedGroupId + "' but was fetched as '" + groupId + "'");
        }

        String packaging = normalizePackaging(interpolate(
                firstNonBlank(raw.packaging(),
                        parent == null ? null : parent.packaging(), "jar"), resolved), self);

        Map<String, Managed> managed = management(raw, parent, resolved, repositoryBase,
                source, chain);
        List<PomDependency> dependencies = dependencies(raw.dependencies(), managed, resolved, self);

        return new Effective(interpolatedGroupId, artifactId, interpolatedVersion, packaging,
                resolved, managed, dependencies);
    }

    /** Own managed entries override the parent's; BOM imports fill only gaps. */
    private static Map<String, Managed> management(
            RawPom raw, Effective parent, Map<String, String> resolved,
            String repositoryBase, PomSource source, Set<String> chain) {

        Map<String, Managed> managed = new LinkedHashMap<>(
                parent == null ? Map.of() : parent.managed());
        Map<String, Managed> own = new LinkedHashMap<>();
        List<Effective> importedBoms = new ArrayList<>();
        for (RawPom.RawDependency entry : raw.managedDependencies()) {
            String groupId = requireField(entry.groupId(), "a managed dependency", "groupId");
            String artifactId = requireField(entry.artifactId(), "a managed dependency", "artifactId");
            String scope = interpolate(entry.scope(), resolved);
            String type = interpolate(entry.type(), resolved);
            String version = interpolate(entry.version(), resolved);
            if ("pom".equals(type) && "import".equals(scope)) {
                requireConcrete(groupId, artifactId, version, "the imported BOM");
                importedBoms.add(load(groupId, artifactId, version, repositoryBase, source, chain));
            } else {
                if (blank(version)) {
                    throw new PomException("managed dependency " + key(groupId, artifactId)
                            + " has no version");
                }
                requireConcrete(groupId, artifactId, version, "the managed dependency");
                own.put(key(groupId, artifactId), new Managed(version, scope,
                        exclusions(entry, resolved)));
            }
        }
        managed.putAll(own);
        for (Effective bom : importedBoms) {
            bom.managed().forEach(managed::putIfAbsent);
        }
        return managed;
    }

    /**
     * The dependencies another project inherits from this pom: compile and
     * runtime only, non-optional, versions resolved through management.
     */
    private static List<PomDependency> dependencies(
            List<RawPom.RawDependency> rawDependencies, Map<String, Managed> managed,
            Map<String, String> resolved, String self) {

        List<PomDependency> result = new ArrayList<>();
        for (RawPom.RawDependency raw : rawDependencies) {
            String groupId = requireField(
                    interpolate(raw.groupId(), resolved), "a dependency of " + self, "groupId");
            String artifactId = requireField(
                    interpolate(raw.artifactId(), resolved), "a dependency of " + self, "artifactId");
            boolean optional = raw.optional() != null && raw.optional().equalsIgnoreCase("true");
            if (optional) {
                // Optional dependencies do not propagate to consumers.
                continue;
            }
            String declaredScope = interpolate(raw.scope(), resolved);
            String scope;
            if (blank(declaredScope)) {
                scope = "compile";
            } else {
                scope = switch (declaredScope) {
                    case "compile", "runtime" -> declaredScope;
                    // test/provided/system never propagate transitively; import
                    // belongs to dependency management, not dependencies.
                    case "test", "provided", "system", "import" -> null;
                    default -> throw new PomException("dependency " + key(groupId, artifactId)
                            + " of " + self + " has unknown scope '" + declaredScope + "'");
                };
                if (scope == null) {
                    continue;
                }
            }
            String type = normalizeType(interpolate(raw.type(), resolved), groupId, artifactId, self);
            String classifier = blank(raw.classifier()) ? null : interpolate(raw.classifier(), resolved);
            List<PomDependency.Exclusion> exclusions = exclusions(raw, resolved);

            String version = interpolate(raw.version(), resolved);
            if (blank(version)) {
                Managed byManagement = managed.get(key(groupId, artifactId));
                if (byManagement == null) {
                    throw new PomException("dependency " + key(groupId, artifactId) + " of "
                            + self + " has no version and none is managed");
                }
                version = byManagement.version();
                if (blank(declaredScope) && !blank(byManagement.scope())) {
                    scope = byManagement.scope();
                }
                List<PomDependency.Exclusion> merged = new ArrayList<>(exclusions);
                byManagement.exclusions().stream()
                        .filter(e -> !merged.contains(e))
                        .forEach(merged::add);
                exclusions = List.copyOf(merged);
            } else {
                requireConcrete(groupId, artifactId, version, "the dependency");
            }
            result.add(new PomDependency(groupId, artifactId, version, scope, type,
                    classifier, exclusions));
        }
        return List.copyOf(result);
    }

    private static List<PomDependency.Exclusion> exclusions(
            RawPom.RawDependency raw, Map<String, String> resolved) {
        List<PomDependency.Exclusion> exclusions = new ArrayList<>();
        for (RawPom.RawDependency.Exclusion exclusion : raw.exclusions()) {
            exclusions.add(new PomDependency.Exclusion(
                    interpolate(exclusion.groupId(), resolved),
                    interpolate(exclusion.artifactId(), resolved)));
        }
        return List.copyOf(exclusions);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static RawPom toRaw(PomXml.Node project) {
        RawPom.Parent parent = null;
        PomXml.Node parentNode = project.first("parent");
        if (parentNode != null) {
            parent = new RawPom.Parent(
                    parentNode.child("groupId").text(),
                    parentNode.child("artifactId").text(),
                    parentNode.child("version").text());
        }

        Map<String, String> properties = new LinkedHashMap<>();
        PomXml.Node propertySection = project.first("properties");
        if (propertySection != null) {
            for (PomXml.Node property : propertySection.children()) {
                properties.put(property.name(), property.text());
            }
        }

        return new RawPom(
                parent,
                project.text("groupId"),
                project.text("artifactId"),
                project.text("version"),
                project.text("packaging"),
                Map.copyOf(properties),
                dependencies(project.first("dependencies")),
                managed(project.first("dependencyManagement")));
    }

    private static List<RawPom.RawDependency> dependencies(PomXml.Node section) {
        if (section == null) {
            return List.of();
        }
        List<RawPom.RawDependency> result = new ArrayList<>();
        for (PomXml.Node dependency : section.all("dependency")) {
            result.add(toRawDependency(dependency));
        }
        return List.copyOf(result);
    }

    private static List<RawPom.RawDependency> managed(PomXml.Node dependencyManagement) {
        if (dependencyManagement == null) {
            return List.of();
        }
        return dependencies(dependencyManagement.first("dependencies"));
    }

    private static RawPom.RawDependency toRawDependency(PomXml.Node dependency) {
        List<RawPom.RawDependency.Exclusion> exclusions = new ArrayList<>();
        PomXml.Node exclusionSection = dependency.first("exclusions");
        if (exclusionSection != null) {
            for (PomXml.Node exclusion : exclusionSection.all("exclusion")) {
                exclusions.add(new RawPom.RawDependency.Exclusion(
                        exclusion.text("groupId"), exclusion.text("artifactId")));
            }
        }
        return new RawPom.RawDependency(
                dependency.text("groupId"),
                dependency.text("artifactId"),
                dependency.text("version"),
                dependency.text("type"),
                dependency.text("classifier"),
                dependency.text("scope"),
                dependency.text("optional"),
                List.copyOf(exclusions));
    }

    private static String normalizePackaging(String packaging, String self) {
        return switch (packaging) {
            case "jar", "bundle" -> "jar";
            case "pom" -> "pom";
            default -> throw new PomException("pom for " + self + " has unsupported packaging '"
                    + packaging + "' (supported: jar, bundle, pom)");
        };
    }

    private static String normalizeType(String type, String groupId, String artifactId, String self) {
        return switch (blank(type) ? "jar" : type) {
            case "jar", "bundle" -> "jar";
            case "pom" -> "pom";
            default -> throw new PomException("dependency " + key(groupId, artifactId) + " of "
                    + self + " has unsupported type '" + type + "' (supported: jar, bundle, pom)");
        };
    }

    /**
     * Resolves the property map to a fixpoint — properties may reference each
     * other — so a value that still contains {@code ${...}} after this is
     * unresolvable and surfaces where it is used.
     */
    private static Map<String, String> resolveProperties(Map<String, String> properties) {
        Map<String, String> current = new LinkedHashMap<>(properties);
        for (int round = 0; round < 10; round++) {
            boolean changed = false;
            for (Map.Entry<String, String> entry : current.entrySet()) {
                String value = entry.getValue();
                if (value != null && value.contains("${")) {
                    String next = interpolate(value, current);
                    if (!next.equals(value)) {
                        entry.setValue(next);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return current;
            }
        }
        return current;
    }

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    /** Replaces known {@code ${...}} references; unknown ones stay verbatim. */
    private static String interpolate(String raw, Map<String, String> properties) {
        if (raw == null || !raw.contains("${")) {
            return raw;
        }
        Matcher matcher = PROPERTY.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = properties.get(matcher.group(1));
            matcher.appendReplacement(out, replacement == null
                    ? Matcher.quoteReplacement(matcher.group(0))
                    : Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static void requireConcrete(String groupId, String artifactId, String version, String what) {
        String named = what + " (" + key(groupId, artifactId) + ")";
        if (version == null || version.isBlank()) {
            throw new PomException(named + " has no version");
        }
        if (version.contains("${")) {
            throw new PomException(named + " uses unresolvable property '" + version + "'");
        }
        if (version.startsWith("[") || version.startsWith("(")) {
            throw new PomException(named + " uses version range '" + version
                    + "' — version ranges are not supported; mj pins exact versions");
        }
        if (version.endsWith("-SNAPSHOT")) {
            throw new PomException(named + " uses snapshot version '" + version
                    + "' — snapshots are not supported");
        }
    }

    private static String requireField(String value, String what, String field) {
        if (blank(value)) {
            throw new PomException(what + " has no " + field);
        }
        return value;
    }

    private static String key(String groupId, String artifactId) {
        return groupId + ":" + artifactId;
    }

    private static String key(String groupId, String artifactId, String version) {
        return groupId + ":" + artifactId + ":" + version;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The internal decode result: everything a child needs from its parent
     * (properties, packaging, managed entries) plus the public dependency
     * list. Owned by this package; only {@link #decode} turns it into a
     * {@link Pom}.
     */
    private record Effective(
            String groupId,
            String artifactId,
            String version,
            String packaging,
            Map<String, String> properties,
            Map<String, Managed> managed,
            List<PomDependency> dependencies) {
    }

    /** One managed entry: a resolved version plus the scope and exclusions it supplies. */
    private record Managed(String version, String scope, List<PomDependency.Exclusion> exclusions) {

        Managed {
            if (exclusions == null) {
                exclusions = List.of();
            }
            exclusions = List.copyOf(exclusions);
        }
    }
}
