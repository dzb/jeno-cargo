package com.jujin.mjm.pom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code pom.xml} → {@code mjm.toml} migration (the {@code mjm init --pom}
 * path). This is a translation, not an import: the output is a hand-editable
 * mjm.toml a human reviews before the first {@code mjm lock}, and anything that
 * has no mjm equivalent is reported instead of silently dropped.
 *
 * <p>A local {@code <parent><relativePath>} chain is read from disk (the default
 * is {@code ../pom.xml}): its properties and dependencyManagement are merged
 * before the child's own, exactly as Maven would. A remote parent is never
 * downloaded — its coordinates are reported and its contributions stay
 * unresolved, because migration must not become a network operation.
 *
 * <p>What is reported and skipped: remote parents, build plugins, profiles,
 * {@code <repositories>}, non-jar packaging, exclusions (mjm.toml has no
 * exclusion syntax), pom-packaging modules with no dependencies of their own,
 * and any dependency whose version is still an unresolved property.
 */
public final class PomMigration {

    private PomMigration() {
    }

    /**
     * The translated file, the notes a human must read, and — for a
     * pom-packaging project — the module list it declares.
     */
    public record Result(String mjToml, List<String> notes, List<String> modules,
                         String packaging) {

        public Result(String mjToml, List<String> notes) {
            this(mjToml, notes, List.of(), null);
        }

        public Result {
            notes = notes == null ? List.of() : List.copyOf(notes);
            modules = modules == null ? List.of() : List.copyOf(modules);
        }

        /** A pom-packaging root with modules: it should become a workspace, not a project. */
        public boolean multiModuleRoot() {
            return "pom".equals(packaging) && !modules.isEmpty();
        }
    }

    /** Translates one pom file, following its local parent chain on disk. */
    public static Result translate(Path pomFile) {
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> managed = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<Path> chain = new LinkedHashSet<>();
        PomXml.Node project = read(pomFile, properties, managed, notes, chain);
        return translate(project, properties, managed, notes);
    }

    /** Translates pom text without a file: no local parent can be followed. */
    public static Result translate(String pomText) {
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> managed = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        PomXml.Node project = PomXml.parse(pomText);
        noteParent(project, null, properties, managed, notes, new LinkedHashSet<>());
        collect(project, properties, managed);
        return translate(project, properties, managed, notes);
    }

    // ------------------------------------------------------------------
    // local parent chain
    // ------------------------------------------------------------------

    private static PomXml.Node read(Path pomFile, Map<String, String> properties,
                                    Map<String, String> managed, List<String> notes,
                                    Set<Path> chain) {
        Path normalized;
        try {
            normalized = pomFile.toRealPath();
        } catch (IOException e) {
            throw new PomException("cannot read " + pomFile);
        }
        if (!chain.add(normalized)) {
            addNote(notes, "parent cycle at " + pomFile + " — the chain stops here");
            return PomXml.parse("""
                <project><groupId/><artifactId/><version/></project>
                """);
        }

        PomXml.Node project;
        try {
            project = PomXml.parse(Files.readString(pomFile));
        } catch (IOException e) {
            throw new PomException("cannot read " + pomFile);
        }

        noteParent(project, pomFile, properties, managed, notes, chain);
        collect(project, properties, managed);
        return project;
    }

    /** Reads (or reports) the parent, then merges its maps before the child's. */
    private static void noteParent(PomXml.Node project, Path pomFile,
                                   Map<String, String> properties, Map<String, String> managed,
                                   List<String> notes, Set<Path> chain) {
        PomXml.Node parent = project.first("parent");
        if (parent == null) {
            return;
        }
        String parentGroup = parent.text("groupId");
        String parentArtifact = parent.text("artifactId");
        String parentVersion = parent.text("version");
        String relativePath = parent.text("relativePath");
        String declared = parentGroup + ":" + parentArtifact + ":" + parentVersion;

        Path candidate = null;
        if (pomFile != null) {
            // Maven's rule: an absent relativePath defaults to ../pom.xml, an
            // explicitly empty one disables the local lookup entirely.
            if (relativePath == null) {
                candidate = pomFile.getParent().resolve("../pom.xml").normalize();
            } else if (!relativePath.isBlank()) {
                candidate = pomFile.getParent().resolve(relativePath.strip()).normalize();
            }
        }
        if (candidate != null && Files.isRegularFile(candidate)) {
            if (!coordinatesMatch(candidate, parent)) {
                addNote(notes, "parent " + declared + " names " + candidate
                        + ", whose coordinates do not match — not merged; check versions"
                        + " by hand");
                return;
            }
            read(candidate, properties, managed, notes, chain);
            addNote(notes, "parent " + declared + " read from " + candidate);
            return;
        }
        addNote(notes, "parent " + declared + " is not local"
                + (candidate == null ? "" : " (looked for " + candidate + ")")
                + " — properties and dependencyManagement it contributed are not resolved;"
                + " check versions by hand");
    }

    /** Whether the file at {@code candidate} really is the declared parent. */
    private static boolean coordinatesMatch(Path candidate, PomXml.Node declaredParent) {
        try {
            PomXml.Node file = PomXml.parse(Files.readString(candidate));
            String group = text(file, "groupId");
            String version = text(file, "version");
            String artifact = text(file, "artifactId");
            PomXml.Node grandparent = file.first("parent");
            if (group == null && grandparent != null) {
                group = grandparent.text("groupId");
            }
            if (version == null && grandparent != null) {
                version = grandparent.text("version");
            }
            return equalOrAbsent(declaredParent.text("groupId"), group)
                    && equalOrAbsent(declaredParent.text("artifactId"), artifact)
                    && equalOrAbsent(declaredParent.text("version"), version);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean equalOrAbsent(String declared, String actual) {
        return declared == null || declared.isBlank() || declared.equals(actual);
    }

    private static void collect(PomXml.Node project, Map<String, String> properties,
                                Map<String, String> managed) {
        // ${project.version} inside dependencyManagement must resolve to this
        // module's version while its chain is being read, not to whatever a
        // previously-read module set.
        String moduleGroup = text(project, "groupId");
        String moduleVersion = text(project, "version");
        PomXml.Node parent = project.first("parent");
        if (moduleGroup == null && parent != null) {
            moduleGroup = parent.text("groupId");
        }
        if (moduleVersion == null && parent != null) {
            moduleVersion = parent.text("version");
        }
        if (moduleGroup != null) {
            properties.put("project.groupId", moduleGroup);
        }
        if (moduleVersion != null) {
            properties.put("project.version", moduleVersion);
        }
        String artifact = text(project, "artifactId");
        if (artifact != null) {
            properties.put("project.artifactId", artifact);
        }
        PomXml.Node propertySection = project.first("properties");
        if (propertySection != null) {
            for (PomXml.Node property : propertySection.children()) {
                properties.put(property.name(), property.text());
            }
        }
        PomXml.Node management = project.first("dependencyManagement");
        if (management != null && management.first("dependencies") != null) {
            for (PomXml.Node dependency : management.first("dependencies").all("dependency")) {
                String id = interpolate(dependency.text("groupId"), properties) + ":"
                        + interpolate(dependency.text("artifactId"), properties);
                String version = interpolate(dependency.text("version"), properties);
                if (version != null && !version.isBlank()) {
                    managed.put(id, version);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // translation
    // ------------------------------------------------------------------

    private static Result translate(PomXml.Node project, Map<String, String> properties,
                                    Map<String, String> managed, List<String> notes) {
        String artifactId = text(project, "artifactId");
        String groupId = text(project, "groupId");
        String version = text(project, "version");
        String packaging = text(project, "packaging");

        PomXml.Node parent = project.first("parent");
        if (parent != null) {
            if (groupId == null) {
                groupId = parent.text("groupId");
            }
            if (version == null) {
                version = parent.text("version");
            }
        }
        properties.putIfAbsent("project.groupId", groupId == null ? "" : groupId);
        properties.putIfAbsent("project.artifactId", artifactId == null ? "" : artifactId);
        properties.putIfAbsent("project.version", version == null ? "" : version);

        List<String> modules = new ArrayList<>();
        PomXml.Node modulesSection = project.first("modules");
        if (modulesSection != null) {
            for (PomXml.Node module : modulesSection.all("module")) {
                if (!module.text().isBlank()) {
                    modules.add(module.text().strip());
                }
            }
        }
        if (packaging != null && !"jar".equals(packaging)) {
            addNote(notes, "packaging " + packaging + " is not jar"
                    + (modules.isEmpty() ? "" : " — modules: " + String.join(", ", modules)));
        }
        if (!modules.isEmpty()) {
            addNote(notes, "this pom declares modules (" + String.join(", ", modules)
                    + ") — migrate each module separately, or write mjm.ws.toml and run"
                    + " this again inside each module; mjm does not recurse");
        }
        noteUnrepresentable(project, notes);

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
                    addNote(notes, id + " has no version (and none is managed) — add one by hand");
                    continue;
                }
            }
            if (depVersion.contains("${")) {
                addNote(notes, id + " version '" + depVersion + "' is an unresolved property — "
                        + "mjm.toml does not interpolate; pin it by hand");
                continue;
            }
            if (depVersion.endsWith("-SNAPSHOT")) {
                addNote(notes, id + " is a snapshot — mjm rejects snapshots; pin a released"
                        + " version");
                continue;
            }
            if ("system".equals(scope)) {
                addNote(notes, id + " has scope system (a local jar path) — mjm has no"
                        + " equivalent; publish it to a repository, or use a URL dependency");
                continue;
            }
            if ("import".equals(scope)) {
                addNote(notes, id + " has scope import (a BOM) — mjm resolves poms directly;"
                        + " declare the libraries it pins instead");
                continue;
            }
            if (!dependency.all("exclusions").isEmpty()) {
                addNote(notes, id + " declares exclusions — mjm.toml has no exclusion syntax;"
                        + " remove the unwanted transitive dependency by not depending on"
                        + " this artifact, or accept it and check the depth budget");
            }
            if ((type != null && !type.equals("jar")) || classifier != null) {
                addNote(notes, id + " uses type=" + type + " classifier=" + classifier
                        + " — mjm resolves plain jars only; that artifact is skipped");
                continue;
            }
            if ("provided".equals(scope)) {
                addNote(notes, id + " has scope provided — it is a compile-time contract the"
                        + " container supplies; add it to [deps] only if you compile against it");
            }
            String line = "    \"" + id + "\" = \"" + depVersion + "\"";
            if ("test".equals(scope)) {
                dev.add(line);
            } else if ("optional".equals(dependency.text("optional"))) {
                addNote(notes, id + " is optional — consumers will not inherit it; decide whether"
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
        return new Result(out.toString(), notes, modules, packaging);
    }

    // ------------------------------------------------------------------
    // notes for what mjm cannot carry
    // ------------------------------------------------------------------

    private static void noteUnrepresentable(PomXml.Node project, List<String> notes) {
        PomXml.Node build = project.first("build");
        if (build != null && build.first("plugins") != null
                && !build.first("plugins").all("plugin").isEmpty()) {
            List<String> names = new ArrayList<>();
            for (PomXml.Node plugin : build.first("plugins").all("plugin")) {
                names.add((plugin.text("groupId") == null ? "" : plugin.text("groupId") + ":")
                        + plugin.text("artifactId"));
            }
            addNote(notes, "build plugins are not migrated (" + String.join(", ", names)
                    + ") — run them before mjm build, or replace them with mjm commands");
        }
        PomXml.Node repositories = project.first("repositories");
        if (repositories != null && !repositories.all("repository").isEmpty()) {
            addNote(notes, "repositories are not copied — declare HTTPS Maven repositories as"
                    + " [repos.<name>] type = \"maven\"; http/snapshot repositories are not"
                    + " supported");
        }
        PomXml.Node pluginRepositories = project.first("pluginRepositories");
        if (pluginRepositories != null && !pluginRepositories.all("pluginRepository").isEmpty()) {
            addNote(notes, "pluginRepositories are not copied — mjm has no plugin resolution;"
                    + " use [repos] only for libraries");
        }
        PomXml.Node profiles = project.first("profiles");
        if (profiles != null && !profiles.all("profile").isEmpty()) {
            addNote(notes, "profiles are not migrated — mjm has no profile activation; choose"
                    + " one set of values or use [overrides]/environment-specific manifests");
        }
    }

    private static void addNote(List<String> notes, String note) {
        if (!notes.contains(note)) {
            notes.add(note);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static List<PomXml.Node> dependencies(PomXml.Node project) {
        PomXml.Node section = project.first("dependencies");
        return section == null ? List.of() : section.all("dependency");
    }

    /** Resolves {@code ${property}} against the merged properties, once. */
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
