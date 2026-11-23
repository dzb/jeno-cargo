package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.pom.PomSource;
import com.jujin.mjm.project.DescriptorSource;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.resolve.DigestSource;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.mjm.store.ArtifactStore;
import com.jujin.mjm.store.LockFileReader;

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
    public Command.Category category() {
        return Command.Category.PROJECT;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.OPTIONAL)
                .writes(CommandCapabilities.WriteScope.PROJECT,
                        CommandCapabilities.WriteScope.LOCKS,
                        CommandCapabilities.WriteScope.CACHE)
                .workspace(CommandCapabilities.WorkspaceMode.MODULE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        boolean json = args.contains("--json");
        List<String> cleaned = args.stream().filter(arg -> !arg.equals("--json")).toList();
        if (!json) {
            return executeHuman(cleaned);
        }
        if (cleaned.isEmpty() || cleaned.size() > 2) {
            JsonResult.error("add", 2, "USAGE",
                    "add needs a key and an optional value — mjm add \"group:artifact\" 1.2.3"
                            + " | mjm add mylib gh:owner/repo@v1.0.0").emit(2);
            return 2;
        }

        Path moduleDir = projectDir;
        List<String> moduleArgs = cleaned;
        if (Workspaces.rootAt(projectDir).isPresent()) {
            WorkspaceRun.Run run;
            try {
                run = WorkspaceRun.root(projectDir, cleaned);
            } catch (Workspaces.UsageException e) {
                JsonResult.error("add", 2, "USAGE", e.getMessage()).emit(2);
                return 2;
            } catch (Workspaces.WorkspaceException e) {
                JsonResult.error("add", 1, "WORKSPACE_INVALID", e.getMessage()).emit(1);
                return 1;
            }
            if (run == null || run.module() == null) {
                JsonResult.error("add", 2, "MODULE_REQUIRED",
                        "this directory is a workspace root — say which module: --module <path>")
                        .emit(2);
                return 2;
            }
            moduleDir = run.module();
            moduleArgs = run.args();
        } else if (hasModuleOption(cleaned)) {
            JsonResult.error("add", 2, "MODULE_WITHOUT_WORKSPACE",
                    "--module is only used at a workspace root").emit(2);
            return 2;
        }

        String key = moduleArgs.getFirst();
        String value = moduleArgs.size() == 2 ? moduleArgs.get(1) : null;
        boolean nativeShorthand = key.contains("/") && !key.contains(":");
        if (value == null && !nativeShorthand) {
            JsonResult.error("add", 2, "JSON_DISCOVERY_UNSUPPORTED",
                    "add without a value is a remote discovery mode; pass an explicit version")
                    .emit(2);
            return 2;
        }

        Path targetDir = moduleDir;
        List<String> targetArgs = moduleArgs;
        JsonCapture.Result captured = JsonCapture.run(() ->
                new AddCommand(lockService, store, targetDir).executeHuman(targetArgs));
        if (captured.exitCode() != 0) {
            JsonResult.error("add", captured.exitCode(),
                    captured.exitCode() == 2 ? "USAGE" : "ADD_FAILED", captured.message())
                    .emit(captured.exitCode());
            return captured.exitCode();
        }
        try {
            String id = value == null
                    ? key.substring(key.indexOf('/') + 1, key.indexOf('@'))
                    : key;
            int artifacts = LockFileReader.read(
                    Files.readString(moduleDir.resolve("mjm.lock"))).size();
            JsonObject dependency = JsonUtils.object();
            dependency.put("id", id);
            if (value != null) {
                dependency.put("value", value);
            }
            JsonResult.start("add")
                    .workspace(moduleDir, "single")
                    .put("mode", "mutate")
                    .put("dependency", dependency)
                    .put("manifest", moduleDir.resolve("mjm.toml")
                            .toAbsolutePath().normalize().toString())
                    .put("lock", moduleDir.resolve("mjm.lock")
                            .toAbsolutePath().normalize().toString())
                    .put("artifactCount", artifacts)
                    .put("lockUpdated", true)
                    .emit(0);
            return 0;
        } catch (IOException | RuntimeException e) {
            JsonResult.error("add", 1, "RESULT_UNREADABLE", e.getMessage()).emit(1);
            return 1;
        }
    }

    private int executeHuman(List<String> args) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new AddCommand(lockService, store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        if (args.isEmpty() || args.size() > 2) {
            System.err.println(Ui.errPrefix() + "add needs a key and an optional value — "
                    + "mjm add \"group:artifact\" 1.2.3 | mjm add mylib gh:owner/repo@v1.0.0");
            return 2;
        }
        String key = args.get(0);
        String value = args.size() == 2 ? args.get(1) : null;

        // add writes mjm.toml and mjm.lock; build/test/clean/lock take the same
        // project lock, so a concurrent writer waits instead of interleaving.
        // No nesting: the body calls prepare/commit directly, never refresh.
        return ProjectLock.with(projectDir, () -> add(key, value));
    }

    private static boolean hasModuleOption(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("--module") || arg.startsWith("--module="));
    }

    private int add(String key, String value) {
        String mjToml;
        try {
            mjToml = Files.readString(projectDir.resolve("mjm.toml"));
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        }

        if (value == null) {
            if (key.contains("/") && !key.contains(":")) {
                return addNativeShorthand(key, mjToml);
            }
            return discover(key);
        }
        return addDependency(key, value, mjToml);
    }

    /**
     * {@code mjm add acme/mylib@v1.2.0} — the gh: shape without spelling gh:
     * out. The artifact name is the repository name (DESIGN.md's convention for
     * native releases). A native release has no version index, so the tag is
     * mandatory; there is nothing to list and nothing to guess.
     */
    private int addNativeShorthand(String key, String mjToml) {
        int at = key.indexOf('@');
        if (at < 0) {
            System.err.println(Ui.errPrefix() + "a native dependency has no version index — give the tag: "
                    + "mjm add " + key + "@v1.2.0");
            return 2;
        }
        String origin = key.substring(0, at);
        String tag = key.substring(at + 1).strip();
        int slash = origin.indexOf('/');
        if (slash <= 0 || slash == origin.length() - 1 || origin.indexOf('/', slash + 1) >= 0) {
            System.err.println(Ui.errPrefix() + "native shorthand is owner/repo@tag — got '" + key + "'");
            return 2;
        }
        String repo = origin.substring(slash + 1);
        return addDependency(repo, "gh:" + origin + "@" + tag, mjToml);
    }

    /** One declaration: validate, resolve (prepare), then write. */
    private int addDependency(String key, String value, String mjToml) {
        com.jujin.mjm.project.Requirement requirement;
        try {
            requirement = ProjectReader.parseRequirement(key, value);  // shape validation, SPEC.md §2.1
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        String updated = appendToDeps(mjToml, key, value);
        LockService.Prepared prepared;
        try {
            // parse + resolve + download first: a failure here leaves the disk
            // exactly as it was
            prepared = lockService.prepare(updated);
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        try {
            lockService.commit(projectDir, prepared);
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        System.out.println(Ui.ok("added " + requirement.id() + "@" + value + " — locked "
                + prepared.graph().artifacts().size() + " artifact(s) into mjm.lock"));
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
            System.err.println(Ui.errPrefix() + "add without a version asks the repository for its "
                    + "version list — pass an explicit version, or drop --offline");
            return 2;
        }
        try {
            if (key.contains(":")) {
                List<String> versions = MetadataVersions.central(key);
                if (versions.isEmpty()) {
                    System.err.println(Ui.errPrefix() + "no versions found for " + key
                            + " — if it belongs to a named repository, declare it as \""
                            + key + "\" = \"<repo>:<version>\" in mjm.toml");
                    return 1;
                }
                System.out.println(Ui.heading("versions of " + key + " (newest last):"));
                for (String version : versions) {
                    System.out.println("  " + version);
                }
                System.out.println("choose one: mjm add \"" + key + "\" <version>");
                return 0;
            }
            return search(key);
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
    }

    /** Central's index search: bare name in, group:artifact + latest version out. */
    private int search(String name) {
        String host = System.getProperty("mjm.search.host", "https://search.maven.org");
        String url = host + "/solrsearch/select?q=a:" + name + "&rows=20&wt=json";
        String body = MetadataVersions.text(url);
        Object response = JsonUtils.parseObject(body).toMap().get("response");
        if (!(response instanceof Map<?, ?> responseMap)
                || !(responseMap.get("docs") instanceof List<?> docs)) {
            System.err.println(Ui.errPrefix() + "unexpected search response from " + host);
            return 1;
        }
        if (docs.isEmpty()) {
            System.err.println(Ui.errPrefix() + "no artifacts found for '" + name + "' on " + host);
            return 1;
        }
        System.out.println(Ui.heading("search '" + name + "' on " + host + ":"));
        for (Object docObject : docs) {
            if (docObject instanceof Map<?, ?> doc) {
                System.out.println("  " + doc.get("id") + "  (latest: " + doc.get("latestVersion")
                        + ")");
            }
        }
        System.out.println(Ui.dim("add one: mjm add \"<group:artifact>\" <version>"));
        return 0;
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
