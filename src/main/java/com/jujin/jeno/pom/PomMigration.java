package com.jujin.jeno.pom;

import com.jujin.jeno.resolve.ArtifactLocations;

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

    /** Translates one pom file, following its local parent chain only. */
    public static Result translate(Path pomFile) {
        return translate(pomFile, null);
    }

    /**
     * Translates one pom file: a local parent is read from disk first; when it
     * is not there, {@code pomSource} may fetch the declared parent from
     * Central (through the store, so {@code --offline} and cache rules apply).
     * A null source means "local only" — the migration still produces a file,
     * with a note.
     */
    public static Result translate(Path pomFile, PomSource pomSource) {
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> managed = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<Path> chain = new LinkedHashSet<>();
        Set<String> remoteChain = new LinkedHashSet<>();
        PomXml.Node project = read(pomFile, pomSource, properties, managed, notes,
                chain, remoteChain);
        return translate(project, properties, managed, notes);
    }

    /** Translates pom text without a file: no local parent can be followed. */
    public static Result translate(String pomText) {
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> managed = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        PomXml.Node project = PomXml.parse(pomText);
        noteParent(project, null, null, properties, managed, notes,
                new LinkedHashSet<>(), new LinkedHashSet<>());
        collect(project, properties, managed, notes);
        return translate(project, properties, managed, notes);
    }

    // ------------------------------------------------------------------
    // local parent chain
    // ------------------------------------------------------------------

    private static PomXml.Node read(Path pomFile, PomSource pomSource,
                                    Map<String, String> properties,
                                    Map<String, String> managed, List<String> notes,
                                    Set<Path> chain, Set<String> remoteChain) {
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

        noteParent(project, pomFile, pomSource, properties, managed, notes, chain,
                remoteChain);
        collect(project, properties, managed, notes, pomSource, new LinkedHashSet<>(), 0);
        return project;
    }

    /** Reads (or reports) the parent, then merges its maps before the child's. */
    private static void noteParent(PomXml.Node project, Path pomFile, PomSource pomSource,
                                   Map<String, String> properties, Map<String, String> managed,
                                   List<String> notes, Set<Path> chain,
                                   Set<String> remoteChain) {
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
            read(candidate, pomSource, properties, managed, notes, chain, remoteChain);
            addNote(notes, "parent " + declared + " read from " + candidate);
            return;
        }
        readRemote(parent, declared, pomSource, properties, managed, notes, remoteChain);
    }

    /**
     * Fetches a declared parent from Central. Snapshots and unresolved versions
     * are never requested; anything that fails stays a note, because migration
     * must still produce a reviewable file.
     */
    private static void readRemote(PomXml.Node parent, String declared, PomSource pomSource,
                                   Map<String, String> properties, Map<String, String> managed,
                                   List<String> notes, Set<String> remoteChain) {
        String group = parent.text("groupId");
        String artifact = parent.text("artifactId");
        String version = parent.text("version");
        if (pomSource == null) {
            addNote(notes, "parent " + declared + " is not local and no source is available"
                    + " — properties and dependencyManagement it contributed are not resolved;"
                    + " check versions by hand");
            return;
        }
        if (group == null || artifact == null || version == null) {
            addNote(notes, "parent " + declared + " has incomplete coordinates — not fetched");
            return;
        }
        if (version.contains("${")) {
            addNote(notes, "parent " + declared + " has an unresolved version — not fetched;"
                    + " pin it by hand");
            return;
        }
        if (version.endsWith("-SNAPSHOT")) {
            addNote(notes, "parent " + declared + " is a snapshot — Central lookup is skipped;"
                    + " use the released parent or copy the versions by hand");
            return;
        }
        String key = group + ":" + artifact + ":" + version;
        if (!remoteChain.add(key)) {
            addNote(notes, "parent cycle at " + key + " — the chain stops here");
            return;
        }
        String text;
        try {
            text = pomSource.pomXml(group, artifact, version, ArtifactLocations.CENTRAL_BASE);
        } catch (RuntimeException e) {
            addNote(notes, "parent " + declared + " could not be fetched (" + e.getMessage()
                    + ") — its properties and dependencyManagement are not resolved;"
                    + " check versions by hand");
            return;
        }
        if (text == null || text.isBlank()) {
            addNote(notes, "parent " + declared + " was not found on Central — its properties"
                    + " and dependencyManagement are not resolved; check versions by hand");
            return;
        }
        PomXml.Node remote;
        try {
            remote = PomXml.parse(text);
        } catch (RuntimeException e) {
            addNote(notes, "parent " + declared + " could not be parsed (" + e.getMessage()
                    + ") — check versions by hand");
            return;
        }
        if (!remoteCoordinatesMatch(remote, group, artifact, version)) {
            addNote(notes, "Central returned different coordinates for parent " + declared
                    + " — not merged; check versions by hand");
            return;
        }
        addNote(notes, "parent " + declared + " read from Central");
        // the remote parent may have its own parent: resolve that first
        noteParent(remote, null, pomSource, properties, managed, notes,
                new LinkedHashSet<>(), remoteChain);
        collect(remote, properties, managed, notes, pomSource, new LinkedHashSet<>(), 0);
    }

    private static boolean remoteCoordinatesMatch(PomXml.Node remote, String group,
                                                  String artifact, String version) {
        String remoteGroup = text(remote, "groupId");
        String remoteVersion = text(remote, "version");
        PomXml.Node grandparent = remote.first("parent");
        if (remoteGroup == null && grandparent != null) {
            remoteGroup = grandparent.text("groupId");
        }
        if (remoteVersion == null && grandparent != null) {
            remoteVersion = grandparent.text("version");
        }
        return group.equals(remoteGroup) && artifact.equals(text(remote, "artifactId"))
                && version.equals(remoteVersion);
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

    private static final int MAX_BOM_DEPTH = 8;

    private static void collect(PomXml.Node project, Map<String, String> properties,
                                Map<String, String> managed, List<String> notes) {
        collect(project, properties, managed, notes, null, new LinkedHashSet<>(), 0);
    }

    private static void collect(PomXml.Node project, Map<String, String> properties,
                                Map<String, String> managed, List<String> notes,
                                PomSource pomSource, Set<String> bomChain, int depth) {
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
                if ("import".equals(dependency.text("scope"))) {
                    String bomVersion = version;
                    if (bomVersion == null || bomVersion.isBlank()) {
                        bomVersion = managed.get(id);
                    }
                    importBom(id, bomVersion, dependency.text("type"), managed, notes,
                            pomSource, bomChain, depth);
                    continue;
                }
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
        Integer javaVersion = javaVersion(properties, notes);
        if (javaVersion != null) {
            out.append("\n[java]\nversion = ").append(javaVersion).append('\n');
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

    /**
     * Fetches one imported BOM (Central/store only) and merges the versions it
     * manages into {@code managed} with putIfAbsent — an explicit entry in the
     * child always wins. The BOM's properties are used for its own resolution
     * and never leak into the child's interpolation namespace.
     */
    private static void importBom(String id, String version, String type,
                                  Map<String, String> managed, List<String> notes,
                                  PomSource pomSource, Set<String> bomChain, int depth) {
        if (version == null || version.isBlank()) {
            addNote(notes, "BOM " + id + " has no resolvable version — it is not fetched;"
                    + " pin its libraries by hand");
            return;
        }
        if (version.contains("${")) {
            addNote(notes, "BOM " + id + " version '" + version + "' is unresolved —"
                    + " it is not fetched; pin its libraries by hand");
            return;
        }
        if (version.endsWith("-SNAPSHOT")) {
            addNote(notes, "BOM " + id + " is a snapshot — Central lookup is skipped;"
                    + " use a released BOM or pin its libraries by hand");
            return;
        }
        if (type != null && !type.equals("pom") && !type.equals("jar")) {
            addNote(notes, "BOM " + id + " has type=" + type + " — unsupported; pin its"
                    + " libraries by hand");
            return;
        }
        if (pomSource == null) {
            addNote(notes, "BOM " + id + ":" + version + " is imported but no source is"
                    + " available — its versions are not applied; pin them by hand");
            return;
        }
        if (depth >= MAX_BOM_DEPTH) {
            addNote(notes, "BOM " + id + ":" + version + " exceeds the BOM import depth ("
                    + MAX_BOM_DEPTH + ") — its versions are not applied; pin them by hand");
            return;
        }
        String key = id + ":" + version;
        Set<String> chain = new LinkedHashSet<>(bomChain);
        if (!chain.add(key)) {
            addNote(notes, "BOM import cycle at " + key + " — the chain stops here");
            return;
        }
        String text;
        try {
            text = pomSource.pomXml(group(id), artifact(id), version,
                    ArtifactLocations.CENTRAL_BASE);
        } catch (RuntimeException e) {
            addNote(notes, "BOM " + key + " could not be fetched (" + e.getMessage()
                    + ") — its versions are not applied; pin them by hand");
            return;
        }
        if (text == null || text.isBlank()) {
            addNote(notes, "BOM " + key + " was not found on Central — its versions are not"
                    + " applied; pin them by hand");
            return;
        }
        PomXml.Node bom;
        try {
            bom = PomXml.parse(text);
        } catch (RuntimeException e) {
            addNote(notes, "BOM " + key + " could not be parsed (" + e.getMessage()
                    + ") — pin its libraries by hand");
            return;
        }
        if (!remoteCoordinatesMatch(bom, group(id), artifact(id), version)) {
            addNote(notes, "Central returned different coordinates for BOM " + key
                    + " — not merged; pin its libraries by hand");
            return;
        }
        addNote(notes, "BOM " + key + " read from Central");

        // The BOM resolves in its own namespace: its parent chain and its own
        // imports use bomProperties/bomManaged, and only versions come back.
        Map<String, String> bomProperties = new LinkedHashMap<>();
        Map<String, String> bomManaged = new LinkedHashMap<>();
        noteParent(bom, null, pomSource, bomProperties, bomManaged, notes,
                new LinkedHashSet<>(), new LinkedHashSet<>());
        collect(bom, bomProperties, bomManaged, notes, pomSource, chain, depth + 1);
        bomManaged.forEach(managed::putIfAbsent);
    }

    private static String group(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }

    private static String artifact(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "" : id.substring(colon + 1);
    }

    /**
     * The target major Maven's properties declare, if they declare one.
     * Precedence follows what actually drives javac: release, then java.version,
     * then compiler target/source. A value that cannot be a number stays out of
     * the output with a note — guessing a JDK here would be inventing a fact.
     */
    private static Integer javaVersion(Map<String, String> properties, List<String> notes) {
        String[] keys = {"maven.compiler.release", "java.version",
            "maven.compiler.target", "maven.compiler.source"};
        for (String key : keys) {
            String raw = properties.get(key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.strip();
            if (value.contains("${")) {
                addNote(notes, "cannot derive [java].version — " + key + " = '" + value
                        + "' is an unresolved property; set [java].version by hand");
                return null;
            }
            Integer major = javaMajor(value);
            if (major == null) {
                addNote(notes, "cannot derive [java].version — " + key + " = '" + value
                        + "' is not a Java major; set [java].version by hand");
                return null;
            }
            if (major < 1 || major > 100) {
                addNote(notes, "cannot derive [java].version — " + key + " = '" + value
                        + "' is outside 1..100; set [java].version by hand");
                return null;
            }
            return major;
        }
        return null;
    }

    /** "1.8" and "1.7" are the pre-9 spellings of 8 and 7; "17" is itself. */
    private static Integer javaMajor(String value) {
        try {
            if (value.startsWith("1.")) {
                return Integer.parseInt(value.substring(2));
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
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
