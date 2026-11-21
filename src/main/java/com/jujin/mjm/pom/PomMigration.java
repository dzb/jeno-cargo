package com.jujin.mjm.pom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code pom.xml} → {@code mjm.toml} migration (the {@code mjm init --pom}
 * path). This is a translation, not an import: the output is a hand-editable
 * mjm.toml a human reviews before the first {@code mjm lock}, and anything that
 * has no mj equivalent is reported instead of silently dropped.
 *
 * <p>What carries over:
 * <ul>
 *   <li>{@code groupId/artifactId/version} → {@code [project] name/version}
 *       (the artifactId becomes the name; the group is reported, not
 *       invented)</li>
 *   <li>{@code <properties>} are resolved into the dependency versions
 *       (mjm.toml has no property interpolation by design, SPEC §2.1)</li>
 *   <li>dependencies with scope {@code test} → {@code [dev-deps]}; every
 *       other compile/runtime scope → {@code [deps]}</li>
 *   <li>{@code <dependencyManagement>} supplies versions for managed
 *       dependencies whose own {@code <version>} is absent</li>
 * </ul>
 *
 * <p>What is reported and skipped: a parent chain that cannot be resolved
 * without network (the parent's coordinates are listed so the human can pin
 * what it contributed), pom-packaging modules with no dependencies of their
 * own, and any dependency whose version is still an unresolved property.
 */
public final class PomMigration {

    private PomMigration() {
    }

    /** The translated file plus the notes a human must read before locking. */
    public record Result(String mjToml, List<String> notes) {

        public Result {
            if (notes == null) {
                notes = List.of();
            }
            notes = List.copyOf(notes);
        }
    }

    /** Translates one pom's text. Never throws for a well-formed pom. */
    public static Result translate(String pomText) {
        PomXml.Node project = PomXml.parse(pomText);
        List<String> notes = new ArrayList<>();

        String artifactId = text(project, "artifactId");
        String groupId = text(project, "groupId");
        String version = text(project, "version");

        PomXml.Node parent = project.first("parent");
        if (parent != null) {
            String parentGroup = parent.text("groupId");
            String parentArtifact = parent.text("artifactId");
            String parentVersion = parent.text("version");
            if (groupId == null) {
                groupId = parentGroup;
            }
            if (version == null) {
                version = parentVersion;
            }
            notes.add("parent " + parentGroup + ":" + parentArtifact + ":" + parentVersion
                    + " is not downloaded — properties and dependencyManagement it"
                    + " contributed are not resolved; check versions by hand");
        }

        Map<String, String> properties = new LinkedHashMap<>();
        PomXml.Node propertySection = project.first("properties");
        if (propertySection != null) {
            for (PomXml.Node property : propertySection.children()) {
                properties.put(property.name(), property.text());
            }
        }
        properties.putIfAbsent("project.groupId", groupId == null ? "" : groupId);
        properties.putIfAbsent("project.artifactId", artifactId == null ? "" : artifactId);
        properties.putIfAbsent("project.version", version == null ? "" : version);

        Map<String, String> managed = managedVersions(project, properties);

        List<String> main = new ArrayList<>();
        List<String> dev = new ArrayList<>();
        for (PomXml.Node dependency : dependencies(project)) {
            String depGroup = interpolate(dependency.text("groupId"), properties);
            String depArtifact = interpolate(dependency.text("artifactId"), properties);
            String depVersion = interpolate(dependency.text("version"), properties);
            String scope = dependency.text("scope");
            String type = dependency.text("type");
            String classifier = dependency.text("classifier");

            String id = depGroup + ":" + depArtifact;
            if (depVersion == null || depVersion.isBlank()) {
                String fromManagement = managed.get(id);
                if (fromManagement != null) {
                    depVersion = fromManagement;
                } else {
                    notes.add(id + " has no version (and none is managed) — add one by hand");
                    continue;
                }
            }
            if (depVersion.contains("${")) {
                notes.add(id + " version '" + depVersion + "' is an unresolved property — "
                        + "mjm.toml does not interpolate; pin it by hand");
                continue;
            }
            if (depVersion.endsWith("-SNAPSHOT")) {
                notes.add(id + " is a snapshot — mj rejects snapshots; pin a released version");
                continue;
            }
            if ("system".equals(scope)) {
                notes.add(id + " has scope system (a local jar path) — mj has no equivalent;"
                        + " publish it to a repository, or use a URL dependency");
                continue;
            }
            if ("import".equals(scope)) {
                notes.add(id + " has scope import (a BOM) — mj resolves poms directly;"
                        + " declare the libraries it pins instead");
                continue;
            }
            if ((type != null && !type.equals("jar")) || classifier != null) {
                notes.add(id + " uses type=" + type + " classifier=" + classifier
                        + " — mj resolves plain jars only; that artifact is skipped");
                continue;
            }
            if ("provided".equals(scope)) {
                notes.add(id + " has scope provided — it is a compile-time contract the"
                        + " container supplies; add it to [deps] only if you compile against it");
            }
            String line = "    \"" + id + "\" = \"" + depVersion + "\"";
            if ("test".equals(scope)) {
                dev.add(line);
            } else if ("optional".equals(dependency.text("optional"))) {
                notes.add(id + " is optional — consumers will not inherit it; decide whether"
                        + " it belongs in [deps] or behind a profile");
                main.add(line);
            } else {
                main.add(line);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("[project]\n");
        out.append("name = \"").append(artifactId == null ? "unnamed" : artifactId).append("\"\n");
        out.append("version = \"").append(version == null ? "0.1.0" : version).append("\"\n");
        if (groupId != null) {
            out.append("# migrated from Maven groupId ").append(groupId).append('\n');
        }
        if (!main.isEmpty()) {
            out.append("\n[deps]\n");
            main.forEach(line -> out.append(line).append('\n'));
        }
        if (!dev.isEmpty()) {
            out.append("\n[dev-deps]\n");
            dev.forEach(line -> out.append(line).append('\n'));
        }
        return new Result(out.toString(), notes);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Map<String, String> managedVersions(
            PomXml.Node project, Map<String, String> properties) {
        Map<String, String> managed = new LinkedHashMap<>();
        PomXml.Node management = project.first("dependencyManagement");
        if (management == null) {
            return managed;
        }
        PomXml.Node section = management.first("dependencies");
        if (section == null) {
            return managed;
        }
        for (PomXml.Node dependency : section.all("dependency")) {
            String id = interpolate(dependency.text("groupId"), properties) + ":"
                    + interpolate(dependency.text("artifactId"), properties);
            String version = interpolate(dependency.text("version"), properties);
            if (version != null && !version.isBlank()) {
                managed.putIfAbsent(id, version);
            }
        }
        return managed;
    }

    private static List<PomXml.Node> dependencies(PomXml.Node project) {
        PomXml.Node section = project.first("dependencies");
        return section == null ? List.of() : section.all("dependency");
    }

    /** Resolves {@code ${property}} against the pom's own properties, once. */
    private static String interpolate(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String resolved = value;
        for (int pass = 0; pass < 8; pass++) {
            int open = resolved.indexOf("${");
            int close = resolved.indexOf('}', open + 2);
            if (open < 0 || close < 0) {
                break;
            }
            String name = resolved.substring(open + 2, close);
            String replacement = properties.get(name);
            if (replacement == null) {
                return resolved;  // left unresolved; the caller reports it
            }
            resolved = resolved.substring(0, open) + replacement + resolved.substring(close + 1);
        }
        return resolved;
    }

    private static String text(PomXml.Node node, String name) {
        PomXml.Node found = node.first(name);
        if (found == null) {
            return null;
        }
        return found.text().isBlank() ? null : found.text();
    }
}
