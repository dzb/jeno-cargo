package com.jujin.jeno.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one runtime file that may mention the previous product name.
 *
 * <p>Everything user-visible about the old name lives here: file names, prefix
 * and repository-type spellings, descriptor names, migration commands and the
 * environment/home hints. Other code asks this class instead of growing its own
 * copy of the old spelling.
 */
public final class LegacyNames {

    public static final String MANIFEST = "mjm.toml";
    public static final String LOCK = "mjm.lock";
    public static final String WORKSPACE = "mjm.ws.toml";
    public static final String HOME = ".mjm";
    public static final String ENV_PREFIX = "MJM_";
    public static final String REPOSITORY_TYPE = "mjm";
    public static final String REPOSITORY_PREFIX = "mjm:";

    private LegacyNames() {}

    public static String manifest(Path dir) {
        return "found an mjm-era project file " + dir.resolve(MANIFEST)
                + " — rename it and relock: mv " + MANIFEST + " jeno.toml && jeno lock";
    }

    public static String lock(Path dir) {
        return "found an mjm-era lock file " + dir.resolve(LOCK)
                + " — rename it and relock: mv " + LOCK + " jeno.lock && jeno lock";
    }

    public static String workspace(Path dir) {
        return "found an mjm-era workspace file " + dir.resolve(WORKSPACE)
                + " — rename it: mv " + WORKSPACE + " jeno.ws.toml";
    }

    public static String repositoryType() {
        return "repository type \"mjm\" is the old name — write type = \"jeno\"";
    }

    public static String repositoryPrefix(String value) {
        return "'" + value + "' uses the old mjm: prefix — write jeno: (jeno:https://…, "
                + "jeno:local:<dir>, or jeno:http://<private-ip>/…)";
    }

    public static String localDescriptor(Path descriptor) {
        return "this is an mjm-era descriptor " + descriptor
                + " — publish the artifact again with jeno";
    }

    public static String remoteDescriptor(String url) {
        return "no jeno descriptor at " + url
                + " — an mjm-era release descriptor may exist under the old name; "
                + "publish it again with jeno";
    }

    public static String homeHint(Path home) {
        return home + " exists but jeno does not read it — use ~/.jeno or the XDG paths";
    }

    public static String environmentHint(List<String> names) {
        return String.join(", ", names)
                + " set but jeno only reads JENO_* — rename the variables";
    }

    public static Path legacyHome() {
        return Path.of(System.getProperty("user.home", ""), HOME);
    }

    /** Environment variable names mjm used; jeno only reads the JENO_* family. */
    public static List<String> environment() {
        List<String> names = new ArrayList<>();
        for (String key : System.getenv().keySet()) {
            if (key.startsWith(ENV_PREFIX)) {
                names.add(key);
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    /** A local <name>-<version>.mjm.toml sitting next to the expected descriptor. */
    public static Path siblingDescriptor(String url) {
        if (url == null || !url.endsWith(".jeno.toml")) {
            return null;
        }
        try {
            Path descriptor = url.startsWith("file:")
                    ? com.jujin.jeno.resolve.ArtifactLocations.localPath(
                            url, com.jujin.jeno.resolve.ArtifactLocations.projectRoot())
                    : Path.of(url);
            Path sibling = descriptor.resolveSibling(
                    descriptor.getFileName().toString()
                            .replace(".jeno.toml", "." + MANIFEST.replace(".toml", "") + ".toml"));
            return Files.isRegularFile(sibling) ? sibling : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
