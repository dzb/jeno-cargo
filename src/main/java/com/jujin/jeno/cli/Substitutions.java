package com.jujin.jeno.cli;

import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockedArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The workspace, seen as a set of replacements: an artifact id listed as a local
 * module resolves to that module's {@code target/classes} instead of the jar in
 * the store.
 *
 * <p>This is the whole of the workspace feature: nothing else in mjm knows that
 * a lock row can come from a directory. {@link ClasspathAssembler} asks here
 * before it asks the store, so build, test, run and classpath all see local
 * code; packaging asks the same question, so a bundle cannot embed the stale jar
 * while a run uses the fresh classes.
 *
 * <p>A local checkout is not silently trusted. The classes directory must exist
 * (otherwise: run mjm build there) and its build stamp must name the version the
 * module's own {@code mjm.toml} declares (otherwise the classes are an older
 * build). A version difference from the lock row is <em>not</em> an error — the
 * workspace exists precisely to try local versions out — so it is a one-line
 * note, printed once per id per run.
 */
final class Substitutions {

    /** One workspace module: where it lives, what it is called, what it claims. */
    record Module(Path dir, String id, String version) {

        Path classes() {
            return dir.resolve("target/classes");
        }
    }

    private final Path root;
    private final Map<String, Module> byId;
    private final Set<String> noted = new HashSet<>();

    private Substitutions(Path root, Map<String, Module> byId) {
        this.root = root;
        this.byId = byId;
    }

    /** No workspace: every lookup misses and no file is read. */
    static Substitutions none() {
        return new Substitutions(null, Map.of());
    }

    /** The workspace the project sits in, or {@link #none()} when there is none. */
    static Substitutions forProject(Path projectDir) {
        return Workspaces.read(projectDir)
                .map(workspace -> of(workspace.root(), workspace.modules()))
                .orElseGet(Substitutions::none);
    }

    /** Builds the id table from module directories; two modules may not claim one id. */
    static Substitutions of(Path root, List<Path> modules) {
        Map<String, Module> byId = new LinkedHashMap<>();
        for (Path moduleDir : modules) {
            JenoProject project;
            try {
                project = ProjectReader.parse(
                        Files.readString(moduleDir.resolve("mjm.toml")));
            } catch (IOException | RuntimeException e) {
                throw new LocalException("cannot read " + moduleDir.resolve("mjm.toml")
                        + " — " + e.getMessage());
            }
            String id = project.group() == null || project.group().isBlank()
                    ? project.name()
                    : project.group() + ":" + project.name();
            Module module = new Module(moduleDir, id, project.version());
            Module previous = byId.putIfAbsent(id, module);
            if (previous != null) {
                throw new LocalException("two workspace modules claim '" + id + "': "
                        + display(root, previous.dir()) + " and " + display(root, moduleDir));
            }
        }
        return new Substitutions(root, Map.copyOf(byId));
    }

    boolean isEmpty() {
        return byId.isEmpty();
    }

    /** The local module for this id, or null — no validation, no side effect. */
    Module find(String id) {
        return byId.get(id);
    }

    /** The one visual marker: {@code " (local: <path>)"} for a local id, else empty. */
    String marker(String id) {
        Module module = byId.get(id);
        return module == null ? "" : " (local: " + display(root, module.dir()) + ")";
    }

    /** The module's workspace-relative path when this id is local, else null. */
    String localPath(String id) {
        Module module = byId.get(id);
        return module == null ? null : display(root, module.dir());
    }

    /** The module's classes if this id is local, else null. */
    Path classesOrNull(String id) {
        Module module = byId.get(id);
        return module == null ? null : module.classes();
    }

    /**
     * The classes directory of a local module, after checking that it is usable:
     * it exists, and the build stamp names the version its mjm.toml declares.
     * Also prints the local/locked note the first time this run needs the module.
     */
    Path localClasses(Module module, String lockedVersion) {
        Path classes = module.classes();
        if (!Files.isDirectory(classes)) {
            throw new LocalException(module.id() + ": no classes at " + display(root, module.dir())
                    + " — run mjm build in " + display(root, module.dir()) + " first");
        }
        String stamped = stampedVersion(classes);
        if (stamped == null) {
            throw new LocalException(module.id() + ": " + display(root, module.dir())
                    + " has no build stamp (target/classes/META-INF/mjm/version.properties)"
                    + " — run mjm build in " + display(root, module.dir()));
        }
        if (!stamped.equals(module.version())) {
            throw new LocalException(module.id() + ": " + display(root, module.dir())
                    + " was built as " + stamped + " but mjm.toml says " + module.version()
                    + " — run mjm build in " + display(root, module.dir()));
        }
        if (lockedVersion != null && !lockedVersion.equals(module.version())) {
            noteOnce(module, lockedVersion);
        }
        return classes;
    }

    /**
     * Says that a package/bundle/publish is shipping unpublished classes — a
     * fact the user has to know, since the artifact looks like any other.
     */
    void noteEmbedded(Module module) {
        if (noted.add("embedded:" + module.id())) {
            System.err.println("note: " + display(root, module.dir()) + " (" + module.version()
                    + ") is a local checkout — this artifact contains unpublished classes");
        }
    }

    /** Local first, then the store — the one decision every consumer shares. */
    Path pathOf(LockedArtifact artifact, ArtifactStore store, boolean tolerateMissing) {
        Module module = byId.get(artifact.id());
        if (module != null) {
            return localClasses(module, artifact.version());
        }
        Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
        if (jar == null && !tolerateMissing) {
            throw new LocalException(artifact.id() + "@" + artifact.version()
                    + " is not in the store — run mjm lock");
        }
        return jar;
    }

    List<Module> modules() {
        return List.copyOf(byId.values());
    }

    private void noteOnce(Module module, String lockedVersion) {
        if (noted.add("version:" + module.id())) {
            System.err.println(Ui.errPrefix() + module.id() + ": local " + module.version()
                    + ", locked " + lockedVersion);
        }
    }

    /** The {@code version=} line mjm's build writes next to the classes. */
    private static String stampedVersion(Path classes) {
        Path stamp = classes.resolve("META-INF/mjm/version.properties");
        if (!Files.isRegularFile(stamp)) {
            return null;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(stamp)) {
            properties.load(in);
        } catch (IOException e) {
            return null;
        }
        String version = properties.getProperty("version");
        return version == null || version.isBlank() ? null : version.trim();
    }

    private static String display(Path root, Path dir) {
        if (root == null) {
            return dir.toString();
        }
        try {
            return root.relativize(dir).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return dir.toString();
        }
    }

    /** A local checkout that cannot stand in for the locked jar. */
    static final class LocalException extends RuntimeException {
        LocalException(String message) {
            super(message);
        }
    }

}
