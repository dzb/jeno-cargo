package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.jeno.project.Descriptor;
import com.jujin.jeno.project.DescriptorReader;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.project.Requirement;
import com.jujin.jeno.project.Species;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jeno verify} — a local pre-release gate.
 *
 * <p>It answers the questions a lockfile cannot answer by itself: do the
 * declared requirements still match the pinned rows, are the bytes the lock
 * names actually in the content-addressed store, and is a locally produced
 * publish triple/bundle internally consistent? It never resolves, downloads,
 * writes, or contacts a repository.
 */
public final class VerifyCommand implements Command {

    private final ArtifactStore store;
    private final Path projectDir;

    public VerifyCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String summary() {
        return "verify direct declarations, store bytes, and any built release artifacts";
    }

    @Override
    public String usage() {
        return "";
    }

    @Override
    public String help() {
        return """
               local only: it does not resolve, download, modify project files, or
               contact a repository. It checks manifest/lock direct declarations,
               pinned store bytes, workspace replacements, and any target/dist
               publish triple or bundle SHA256SUMS.""";
    }

    @Override
    public Command.Category category() {
        return Command.Category.PUBLISH;
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.NEVER)
                .workspace(CommandCapabilities.WorkspaceMode.AGGREGATE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        boolean json = args.contains("--json");
        List<String> cleaned = new ArrayList<>();
        for (String arg : args) {
            if (!arg.equals("--json")) {
                cleaned.add(arg);
            }
        }
        if (json) {
            return verifyJson(cleaned);
        }

        Integer workspace = WorkspaceRun.orchestrate(projectDir, cleaned, "verified", true,
                (dir, rest) -> new VerifyCommand(store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        if (!cleaned.isEmpty()) {
            System.err.println(Ui.errPrefix() + "verify takes no arguments — got '"
                    + cleaned.getFirst() + "'");
            return 2;
        }
        return verifyHuman();
    }

    private int verifyHuman() {
        VerifyModule module = inspectModule(projectDir);
        if (module.fatalCode() != null) {
            System.err.println(Ui.errPrefix() + module.fatalMessage());
            return 1;
        }

        System.out.println(Ui.heading("verify: project ") + projectDir);
        for (CheckResult check : module.checks()) {
            render(check);
        }
        boolean ok = module.ok();
        System.out.println(ok ? Ui.ok("verify: ok") : Ui.fail("verify: failed"));
        return ok ? 0 : 1;
    }

    /** JSON is one document even across a workspace; each module is one entry. */
    private int verifyJson(List<String> args) {
        WorkspaceRun.Run run;
        try {
            run = WorkspaceRun.root(projectDir, args);
        } catch (Workspaces.UsageException e) {
            JsonResult.error("verify", 2, "USAGE", e.getMessage()).emit(2);
            return 2;
        } catch (Workspaces.WorkspaceException e) {
            JsonResult.error("verify", 1, "WORKSPACE_INVALID", e.getMessage()).emit(1);
            return 1;
        }

        List<Path> modules;
        String mode;
        Path root = projectDir.toAbsolutePath().normalize();
        if (run == null) {
            if (hasModuleOption(args)) {
                JsonResult.error("verify", 2, "MODULE_WITHOUT_WORKSPACE",
                        "--module is only used at a workspace root").emit(2);
                return 2;
            }
            if (!args.isEmpty()) {
                JsonResult.error("verify", 2, "USAGE",
                        "verify takes no arguments — got '" + args.getFirst() + "'").emit(2);
                return 2;
            }
            modules = List.of(root);
            mode = "single";
        } else {
            if (!run.args().isEmpty()) {
                JsonResult.error("verify", 2, "USAGE",
                        "verify takes no arguments — got '" + run.args().getFirst() + "'")
                        .emit(2);
                return 2;
            }
            try {
                modules = run.module() != null
                        ? List.of(run.module()) : Workspaces.topological(run.workspace());
            } catch (Workspaces.WorkspaceException e) {
                JsonResult.error("verify", 1, "WORKSPACE_INVALID", e.getMessage()).emit(1);
                return 1;
            }
            root = run.workspace().root();
            mode = run.module() != null ? "module" : "all";
        }

        JsonResult result = JsonResult.start("verify").workspace(root, mode);
        JsonArray moduleArray = JsonUtils.array();
        int failedModules = 0;
        int totalChecks = 0;
        int passedChecks = 0;
        int failedChecks = 0;
        int skippedChecks = 0;
        JenoProject singleProject = null;

        for (Path moduleDir : modules) {
            String label = mode.equals("single") ? "."
                    : Workspaces.display(root, moduleDir);
            VerifyModule module = inspectModule(moduleDir);
            JsonObject entry = moduleJson(label, moduleDir, module);
            moduleArray.add(entry);
            if (!module.ok()) {
                failedModules++;
            }
            for (CheckResult check : moduleChecks(module)) {
                totalChecks++;
                switch (check.status()) {
                    case OK -> passedChecks++;
                    case FAILED -> {
                        failedChecks++;
                        result.diagnostic("error", check.id(), check.message(), List.of());
                    }
                    case SKIPPED -> skippedChecks++;
                }
            }
            if (mode.equals("single") && module.project() != null) {
                singleProject = module.project();
            }
        }

        if (singleProject != null) {
            result.project(root, singleProject);
        }
        result.put("modules", moduleArray);

        JsonObject summary = JsonUtils.object();
        summary.put("modules", modules.size());
        summary.put("passedModules", modules.size() - failedModules);
        summary.put("failedModules", failedModules);
        summary.put("checks", totalChecks);
        summary.put("passedChecks", passedChecks);
        summary.put("failedChecks", failedChecks);
        summary.put("skippedChecks", skippedChecks);
        result.put("summary", summary);

        int exit = failedModules == 0 ? 0 : 1;
        result.emit(exit);
        return exit;
    }

    private JsonObject moduleJson(String label, Path moduleDir, VerifyModule module) {
        JsonObject entry = JsonUtils.object();
        entry.put("module", label);
        entry.put("path", moduleDir.toAbsolutePath().normalize().toString());
        entry.put("status", module.ok() ? "ok" : "failed");
        entry.put("exitCode", module.ok() ? 0 : 1);
        if (module.project() != null) {
            JsonObject identity = JsonUtils.object();
            identity.put("name", module.project().name());
            identity.put("version", module.project().version());
            entry.put("project", identity);
        }
        List<CheckResult> checks = moduleChecks(module);
        JsonArray checksJson = JsonUtils.array();
        for (CheckResult check : checks) {
            checksJson.add(check.json());
        }
        entry.put("checks", checksJson);
        return entry;
    }

    private static List<CheckResult> moduleChecks(VerifyModule module) {
        if (module.fatalCode() == null) {
            return module.checks();
        }
        return List.of(CheckResult.failed(module.fatalCode(), module.fatalMessage()));
    }

    private VerifyModule inspectModule(Path dir) {
        JenoProject project;
        List<LockedArtifact> locked;
        try {
            project = ProjectReader.parse(Files.readString(dir.resolve("jeno.toml")));
            locked = LockFileReader.read(Files.readString(dir.resolve("jeno.lock")));
        } catch (IOException e) {
            return VerifyModule.fatal(dir, "MANIFEST_OR_LOCK_UNREADABLE",
                    "cannot read jeno.toml/jeno.lock — run jeno lock");
        } catch (RuntimeException e) {
            return VerifyModule.fatal(dir, "MANIFEST_OR_LOCK_INVALID", e.getMessage());
        }

        List<CheckResult> checks = new ArrayList<>();
        checks.addAll(declarations(project, locked));
        checks.addAll(storeBytes(project, locked, dir));
        checks.addAll(artifacts(project, dir));
        return new VerifyModule(dir, project, List.copyOf(checks), null, null);
    }

    /** Every direct declaration still points at the locked id, version and source. */
    private List<CheckResult> declarations(JenoProject project, List<LockedArtifact> locked) {
        Map<String, LockedArtifact> byId = index(locked);
        List<CheckResult> checks = new ArrayList<>();
        int checked = 0;

        for (Requirement requirement : project.requirements()) {
            checked++;
            LockedArtifact row = byId.get(requirement.id());
            if (row == null) {
                checks.add(CheckResult.failed("declaration.not-locked",
                        "declared '" + requirement.id() + "' is not in jeno.lock — run jeno lock"));
                continue;
            }
            if (!row.version().equals(requirement.version())) {
                checks.add(CheckResult.failed("declaration.version-mismatch",
                        requirement.id() + ": jeno.toml " + requirement.version()
                        + ", jeno.lock " + row.version() + " — run jeno lock"));
            }
            String source = expectedSource(project, requirement);
            if (source != null && !row.source().equals(source)) {
                checks.add(CheckResult.failed("declaration.source-mismatch",
                        requirement.id() + ": jeno.toml source " + source
                        + ", jeno.lock source " + row.source() + " — run jeno lock"));
            }
            if (row.scope() != com.jujin.jeno.resolve.Scope.MAIN) {
                checks.add(CheckResult.failed("declaration.scope-mismatch",
                        requirement.id() + ": [deps] is MAIN but the lock row is "
                        + row.scope().name().toLowerCase(java.util.Locale.ROOT)
                        + " — run jeno lock"));
            }
        }
        for (Requirement requirement : project.devRequirements()) {
            checked++;
            LockedArtifact row = byId.get(requirement.id());
            if (row == null) {
                checks.add(CheckResult.failed("declaration.dev-not-locked",
                        "declared dev dependency '" + requirement.id()
                        + "' is not in jeno.lock — run jeno lock"));
            } else if (!row.version().equals(requirement.version())) {
                checks.add(CheckResult.failed("declaration.dev-version-mismatch",
                        "declared dev dependency '" + requirement.id()
                        + "' does not match jeno.lock — run jeno lock"));
            } else {
                String source = expectedSource(project, requirement);
                if (source != null && !row.source().equals(source)) {
                    checks.add(CheckResult.failed("declaration.dev-source-mismatch",
                            requirement.id() + ": dev jeno.toml source " + source
                            + ", jeno.lock source " + row.source() + " — run jeno lock"));
                }
            }
        }
        for (Requirement requirement : project.processorRequirements()) {
            checked++;
            LockedArtifact row = byId.get(requirement.id());
            if (row == null) {
                checks.add(CheckResult.failed("declaration.processor-not-locked",
                        "declared processor '" + requirement.id()
                        + "' is not in jeno.lock — run jeno lock"));
            } else if (!row.version().equals(requirement.version())) {
                checks.add(CheckResult.failed("declaration.processor-version-mismatch",
                        "declared processor '" + requirement.id()
                        + "' does not match jeno.lock — run jeno lock"));
            } else {
                String source = expectedSource(project, requirement);
                if (source != null && !row.source().equals(source)) {
                    checks.add(CheckResult.failed("declaration.processor-source-mismatch",
                            requirement.id() + ": processor jeno.toml source " + source
                            + ", jeno.lock source " + row.source() + " — run jeno lock"));
                }
            }
        }
        if (checks.isEmpty()) {
            JsonObject details = JsonUtils.object();
            details.put("count", checked);
            checks.add(CheckResult.ok("declarations",
                    "direct declarations match the lock (" + checked + ")", details));
        }
        return checks;
    }

    /** The lock's jar and metadata digests must still be present and correct. */
    private List<CheckResult> storeBytes(JenoProject project, List<LockedArtifact> locked,
                                         Path dir) {
        Substitutions substitutions = Substitutions.forProject(dir);
        List<CheckResult> checks = new ArrayList<>();
        int jars = 0;
        int metadata = 0;
        int local = 0;
        for (LockedArtifact artifact : locked) {
            Substitutions.Module module = substitutions.find(artifact.id());
            if (module != null) {
                try {
                    substitutions.localClasses(module, artifact.version());
                    local++;
                } catch (Substitutions.LocalException e) {
                    checks.add(CheckResult.failed("store.local", e.getMessage()));
                }
                continue;
            }
            Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
            if (jar == null) {
                checks.add(CheckResult.failed("store.jar-missing",
                        artifact.id() + "@" + artifact.version()
                        + ": jar is not in the store — run jeno lock"));
            } else if (!ArtifactStore.sha256(jar).equals(artifact.sha256())) {
                checks.add(CheckResult.failed("store.jar-digest",
                        artifact.id() + "@" + artifact.version()
                        + ": store jar digest does not match jeno.lock"));
            } else {
                jars++;
            }
            if (artifact.metaSha256() != null) {
                CheckResult metadataFailure = metadataFailure(artifact);
                if (metadataFailure != null) {
                    checks.add(metadataFailure);
                } else {
                    metadata++;
                }
            }
        }
        if (checks.isEmpty()) {
            JsonObject details = JsonUtils.object();
            details.put("jars", jars);
            details.put("metadata", metadata);
            details.put("local", local);
            checks.add(CheckResult.ok("store", "store bytes verified ("
                    + jars + " jars, " + metadata + " metadata, " + local + " local)", details));
        }
        return checks;
    }

    /** Validate what is already built, without saying that unbuilt output is broken. */
    private List<CheckResult> artifacts(JenoProject project, Path dir) {
        Path dist = dir.resolve("target/dist").toAbsolutePath().normalize();
        if (!Files.isDirectory(dist)) {
            return List.of(CheckResult.skipped("artifacts.dist",
                    "artifacts: no target/dist (skipped)"));
        }
        List<CheckResult> checks = new ArrayList<>();
        String stem = project.name() + "-" + project.version();
        Path jar = dist.resolve(stem + ".jar");
        Path descriptor = dist.resolve(stem + ".jeno.toml");
        Path sha = dist.resolve(stem + ".sha256");
        boolean anyTriple = Files.exists(jar) || Files.exists(descriptor) || Files.exists(sha);
        if (Files.isRegularFile(jar) && !Files.exists(descriptor) && !Files.exists(sha)) {
            // package writes only the jar; jeno publish adds the descriptor and sha256.
            checks.add(CheckResult.skipped("artifacts.triple",
                    "publish triple: not prepared (package artifact present;"
                    + " jeno publish writes the descriptor and sha256)"));
        } else if (anyTriple) {
            if (!Files.isRegularFile(jar) || !Files.isRegularFile(descriptor)
                    || !Files.isRegularFile(sha)) {
                checks.add(CheckResult.failed("artifacts.triple-incomplete",
                        "publish triple is incomplete in " + dist
                        + " — run jeno package, then jeno publish"));
            } else {
                try {
                    DescriptorReader.parse(Files.readString(descriptor),
                            project.name(), project.version());
                    String expected = artifactSha(Files.readString(sha), sha);
                    String actual = ArtifactStore.sha256(jar);
                    if (!actual.equals(expected)) {
                        checks.add(CheckResult.failed("artifacts.triple-digest",
                                sha + " does not match " + jar + " — regenerate the triple"));
                    } else {
                        checks.add(CheckResult.ok("artifacts.triple",
                                "publish triple is internally consistent"));
                    }
                } catch (IOException | RuntimeException e) {
                    checks.add(CheckResult.failed("artifacts.triple-invalid",
                            "publish triple: " + e.getMessage()));
                }
            }
        } else {
            checks.add(CheckResult.skipped("artifacts.triple",
                    "publish triple: not built (skipped)"));
        }

        Path bundle = dist.resolve(stem);
        Path sums = bundle.resolve("SHA256SUMS");
        if (Files.isRegularFile(sums)) {
            checks.addAll(checksums(bundle, sums));
        } else if (Files.isDirectory(bundle)) {
            checks.add(CheckResult.failed("artifacts.bundle-incomplete",
                    "bundle is incomplete at " + bundle + " — run jeno bundle"));
        } else {
            checks.add(CheckResult.skipped("artifacts.bundle",
                    "bundle: not built (skipped)"));
        }
        return checks;
    }

    private List<CheckResult> checksums(Path root, Path sums) {
        List<CheckResult> checks = new ArrayList<>();
        int checked = 0;
        try {
            for (String line : Files.readAllLines(sums)) {
                String stripped = line.strip();
                if (stripped.isEmpty()) {
                    continue;
                }
                int split = stripped.indexOf("  ");
                if (split < 1) {
                    checks.add(CheckResult.failed("artifacts.bundle-sums",
                            "bundle SHA256SUMS line is malformed: " + stripped));
                    continue;
                }
                String expected = stripped.substring(0, split).trim();
                String relative = stripped.substring(split + 2).trim();
                Path file = root.resolve(relative).normalize();
                if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) {
                    checks.add(CheckResult.failed("artifacts.bundle-sums",
                            "bundle SHA256SUMS names a missing/escaping file: " + relative));
                } else if (!ArtifactStore.sha256(file).equals(expected)) {
                    checks.add(CheckResult.failed("artifacts.bundle-sums",
                            "bundle checksum mismatch: " + relative));
                } else {
                    checked++;
                }
            }
        } catch (IOException e) {
            checks.add(CheckResult.failed("artifacts.bundle-sums",
                    "cannot read bundle SHA256SUMS: " + e.getMessage()));
            return checks;
        }
        if (checks.isEmpty()) {
            JsonObject details = JsonUtils.object();
            details.put("files", checked);
            checks.add(CheckResult.ok("artifacts.bundle",
                    "bundle SHA256SUMS verified (" + checked + " files)", details));
        }
        return checks;
    }

    private String artifactSha(String text, Path file) {
        String sha = text.trim();
        if (!sha.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    file + " is not a 64-character lowercase sha256 — regenerate the triple");
        }
        return sha;
    }

    private static Map<String, LockedArtifact> index(List<LockedArtifact> locked) {
        Map<String, LockedArtifact> byId = new LinkedHashMap<>();
        for (LockedArtifact artifact : locked) {
            byId.put(artifact.id(), artifact);
        }
        return byId;
    }

    private static String expectedSource(JenoProject project, Requirement requirement) {
        if (requirement.species() == Species.GH) {
            return requirement.origin();
        }
        if (requirement.species() == Species.URL) {
            return "url";
        }
        if (requirement.species() == Species.NAMED) {
            return project.repos().get(requirement.repository()).declaration();
        }
        return requirement.repository() == null
                ? "central" : project.repos().get(requirement.repository()).declaration();
    }

    /**
     * Metadata may be a POM or an jeno descriptor. The store extension is part
     * of its kind, so try both rather than re-deriving repository layout from a
     * source string; the content-addressed digest is the fact that matters.
     */
    private CheckResult metadataFailure(LockedArtifact artifact) {
        boolean present = false;
        for (ArtifactStore.Kind kind : List.of(
                ArtifactStore.Kind.POM, ArtifactStore.Kind.DESCRIPTOR)) {
            Path meta = store.findByDigest(artifact.metaSha256(), kind);
            if (meta == null) {
                continue;
            }
            present = true;
            if (ArtifactStore.sha256(meta).equals(artifact.metaSha256())) {
                return null;
            }
        }
        return CheckResult.failed(present ? "store.metadata-digest" : "store.metadata-missing",
                artifact.id() + "@" + artifact.version()
                + (present
                ? ": store metadata digest does not match jeno.lock"
                : ": metadata is not in the store — run jeno lock"));
    }

    private static void render(CheckResult check) {
        switch (check.status()) {
            case OK -> System.out.println("  " + Ui.check(check.message()));
            case FAILED -> System.out.println("  " + Ui.cross(check.message()));
            case SKIPPED -> System.out.println("  " + Ui.dim("- " + check.message()));
        }
    }

    private static boolean hasModuleOption(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("--module") || arg.startsWith("--module="));
    }

    private record VerifyModule(Path dir, JenoProject project, List<CheckResult> checks,
                                String fatalCode, String fatalMessage) {
        static VerifyModule fatal(Path dir, String code, String message) {
            return new VerifyModule(dir, null, List.of(), code, message);
        }

        boolean ok() {
            return fatalCode == null && checks.stream().noneMatch(CheckResult::failed);
        }
    }
}
