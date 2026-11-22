package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.pom.PomSource;
import com.jujin.mjm.project.DescriptorSource;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.resolve.DigestSource;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.mjm.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code mjm add &lt;key&gt; [value]}: appends one dependency to mjm.toml's
 * {@code [deps]} and re-locks. Without a value:
 *
 * <ul>
 *   <li>a Central coordinate ({@code group:artifact}) lists every version
 *       the repository publishes ({@link MetadataVersions})</li>
 *   <li>a bare name searches the Central index ({@code mjm add jackson}) —
 *       the fuzzy search Q1 scheduled for M5</li>
 * </ul>
 */
public final class AddCommand implements Command {

    private final LockService lockService;
    private final ArtifactStore store;
    private final Path projectDir;

    @Inject
    public AddCommand(LockService lockService, ArtifactStore store, Path projectDir) {
        this.lockService = lockService;
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "add";
    }

    @Override
    public String usage() {
        return "<key> [value]";
    }

    @Override
    public String summary() {
        return "add a dependency and re-lock (no value: list versions or search)";
    }

    @Override
    public int execute(List<String> args) {
        if (args.isEmpty() || args.size() > 2) {
            System.err.println("mjm: add needs a key and an optional value — "
                    + "mjm add \"group:artifact\" 1.2.3 | mjm add mylib gh:owner/repo@v1.0.0");
            return 2;
        }
        String key = args.get(0);
        String value = args.size() == 2 ? args.get(1) : null;

        String mjToml;
        try {
            mjToml = Files.readString(projectDir.resolve("mjm.toml"));
        } catch (IOException e) {
            System.err.println("mjm: no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        }

        if (value == null) {
            return discover(key);
        }

        com.jujin.mjm.project.Requirement requirement;
        try {
            requirement = ProjectReader.parseRequirement(key, value);  // shape validation, SPEC.md §2.1
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }
        String updated = appendToDeps(mjToml, key, value);
        LockService.Prepared prepared;
        try {
            // parse + resolve + download first: a failure here leaves the disk
            // exactly as it was
            prepared = lockService.prepare(updated);
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }
        try {
            lockService.commit(projectDir, prepared);
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }
        System.out.println("added " + requirement.id() + "@" + value + " — locked "
                + prepared.graph().artifacts().size() + " artifact(s) into mjm.lock");
        return 0;
    }

    /**
     * One of the two "no value" answers: list versions, or search the index.
     * Both are network calls by nature — the version list is the repository's
     * metadata, the search is the Central index. An explicit version resolves
     * from the store, which is why the command as a whole still accepts
     * {@code --offline}: only this form refuses it.
     */
    private int discover(String key) {
        if (ArtifactStore.isOffline()) {
            System.err.println("mjm: add without a version asks the repository for its "
                    + "version list — pass an explicit version, or drop --offline");
            return 2;
        }
        if (key.contains(":")) {
            List<String> versions = MetadataVersions.central(key);
            if (versions.isEmpty()) {
                System.err.println("mjm: no versions found for " + key);
                return 1;
            }
            System.out.println("versions of " + key + " (newest last):");
            for (String version : versions) {
                System.out.println("  " + version);
            }
            System.out.println("choose one: mjm add \"" + key + "\" <version>");
            return 1;
        }
        return search(key);
    }

    /** Central's index search: bare name in, group:artifact + latest version out. */
    private int search(String name) {
        String host = System.getProperty("mjm.search.host", "https://search.maven.org");
        String url = host + "/solrsearch/select?q=a:" + name + "&rows=20&wt=json";
        String body = MetadataVersions.text(url);
        Object response = JsonUtils.parseObject(body).toMap().get("response");
        if (!(response instanceof Map<?, ?> responseMap)
                || !(responseMap.get("docs") instanceof List<?> docs)) {
            System.err.println("mjm: unexpected search response from " + host);
            return 1;
        }
        System.out.println("search '" + name + "' on " + host + ":");
        for (Object docObject : docs) {
            if (docObject instanceof Map<?, ?> doc) {
                System.out.println("  " + doc.get("id") + "  (latest: " + doc.get("latestVersion")
                        + ")");
            }
        }
        System.out.println("add one: mjm add \"<group:artifact>\" <version>");
        return 1;
    }

    /**
     * Appends one {@code key = value} line to the {@code [deps]} table —
     * before the next section header, never inside a sub-table. The file's
     * comments and layout survive; only one line appears.
     */
    static String appendToDeps(String text, String key, String value) {
        List<String> lines = text.lines().collect(java.util.stream.Collectors.toList());
        int depsLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("[deps]")) {
                depsLine = i;
                break;
            }
        }
        String line = "\"" + key + "\" = \"" + value + "\"";
        if (depsLine < 0) {
            lines.add("");
            lines.add("[deps]");
            lines.add(line);
            return String.join("\n", lines) + "\n";
        }
        int insertAt = depsLine + 1;
        while (insertAt < lines.size() && !lines.get(insertAt).strip().startsWith("[")) {
            insertAt++;
        }
        lines.add(insertAt, line);
        return String.join("\n", lines) + "\n";
    }
}
