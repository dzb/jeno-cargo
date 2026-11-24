package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.project.Repository;
import com.jujin.jeno.resolve.ArtifactLocations;
import com.jujin.jeno.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jeno release [target] [--dry-run]} — one step: take the installer that
 * {@code jeno bundle} produced and publish it.
 *
 * <p>It never builds, tests or bundles: those decisions belong to a project's
 * CI/flow, and a release step that quietly rebuilds is a release step whose
 * bytes nobody reviewed. The only input is
 * {@code target/dist/<name>-<version>.tar.gz}.
 *
 * <p>Without a target it prepares {@code target/dist/release/} and prints the
 * upload checklist. With {@code gh:}, {@code jeno:https://…},
 * {@code jeno:http://<private-ip>/} (LAN only) or {@code jeno:local:<dir>} it
 * uploads the installer, a bootstrap {@code install.sh},
 * a checksum and {@code release.json}, then reads the installer back and
 * compares its sha256 — a publish that cannot be verified is not done.
 */
public final class ReleaseCommand implements Command {

    private static final String DIST = "target/dist";
    private static final String RELEASE = "target/dist/release";

    private final Path projectDir;

    public ReleaseCommand(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "release";
    }

    @Override
    public String usage() {
        return "[target] [--dry-run] [--allow-unsigned] [--notes-file <path>]";
    }

    @Override
    public String summary() {
        return "publish a bundled installer: gh:, jeno:https://host/,"
                + " jeno:http://<private-ip>/, jeno:local:<dir>";
    }

    @Override
    public String help() {
        return """
               needs target/dist/<name>-<version>.tar.gz (run jeno bundle first);
               signed with minisign by default: set JENO_MINISIGN_KEY, and optionally
               JENO_MINISIGN_PUBKEY/JENO_MINISIGN; --allow-unsigned is the explicit opt-out.
               targets: gh:<owner>/<repo>, jeno:https://host/, jeno:http://<private-ip>/
               (literal LAN address only; public hosts need https), jeno:local:<dir>.
               no target prepares target/dist/release/ and prints the upload list.
               --notes-file <path> supplies the gh: release body (gh: target only).
               --dry-run prepares the same files and shows the URLs without uploading.""";
    }

    @Override
    public Command.Category category() {
        return Command.Category.PUBLISH;
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
                .writes(CommandCapabilities.WriteScope.TARGET,
                        CommandCapabilities.WriteScope.REMOTE)
                .workspace(CommandCapabilities.WorkspaceMode.MODULE)
                .dryRun()
                .confirmation(CommandCapabilities.Confirmation.REQUIRES_SIGNATURE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        ProjectFiles.rejectLegacy(projectDir);
        boolean json = args.contains("--json");
        boolean dryRun = args.contains("--dry-run");
        List<String> cleaned = args.stream()
                .filter(arg -> !arg.equals("--json"))
                .toList();
        if (json || dryRun) {
            return planOrExecute(cleaned, json, dryRun);
        }
        return executeHuman(cleaned);
    }

    private int planOrExecute(List<String> args, boolean json, boolean dryRun) {
        Path moduleDir = projectDir;
        List<String> moduleArgs = args;
        if (Workspaces.rootAt(projectDir).isPresent()) {
            WorkspaceRun.Run run;
            try {
                run = WorkspaceRun.root(projectDir, args);
            } catch (Workspaces.UsageException e) {
                return error(json, dryRun, 2, "USAGE", e.getMessage());
            } catch (Workspaces.WorkspaceException e) {
                return error(json, dryRun, 1, "WORKSPACE_INVALID", e.getMessage());
            }
            if (run == null || run.module() == null) {
                return error(json, dryRun, 2, "MODULE_REQUIRED",
                        "this directory is a workspace root — say which module: --module <path>");
            }
            moduleDir = run.module();
            moduleArgs = run.args();
        } else if (moduleArgs.stream().anyMatch(arg -> arg.equals("--module")
                || arg.startsWith("--module="))) {
            return error(json, dryRun, 2, "MODULE_WITHOUT_WORKSPACE",
                    "--module is only used at a workspace root");
        }

        Parsed parsed;
        try {
            parsed = parse(moduleArgs);
        } catch (IllegalArgumentException e) {
            return error(json, dryRun, 2, "USAGE", e.getMessage());
        }

        ReleasePlan plan;
        try {
            plan = planRelease(moduleDir, parsed.target(), parsed.allowUnsigned(), json);
        } catch (RuntimeException e) {
            return error(json, dryRun, 1, "RELEASE_PLAN_FAILED", e.getMessage());
        }
        if (parsed.offlineConflict(plan)) {
            return error(json, dryRun, 2, "OFFLINE_FORBIDDEN",
                    "release to " + parsed.target() + " requires network access");
        }

        if (!dryRun) {
            Path targetDir = moduleDir;
            List<String> targetArgs = moduleArgs;
            JsonCapture.Result captured = JsonCapture.run(() ->
                    new ReleaseCommand(targetDir).executeHuman(targetArgs));
            if (captured.exitCode() != 0) {
                return error(json, dryRun, captured.exitCode(), "RELEASE_FAILED", captured.message());
            }
            plan = planRelease(moduleDir, parsed.target(), parsed.allowUnsigned(), json);
        }

        if (json) {
            emitJson(plan, dryRun);
        } else {
            emitPlan(plan, dryRun);
        }
        return 0;
    }

    private int executeHuman(List<String> args) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new ReleaseCommand(dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        Parsed parsed;
        try {
            parsed = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 2;
        }
        // The wrapper handles dry-run and JSON; executeHuman only performs the
        // real local preparation and upload. The upload reads target/dist/, so
        // the lock covers the network phase too: clean --dist must not pull the
        // tar out from under a running release.
        return ProjectLock.with(projectDir, () -> release(parsed));
    }

    private int release(Parsed parsed) {
        String target = parsed.target();
        boolean allowUnsigned = parsed.allowUnsigned();

        JenoProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("jeno.toml")));
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "no jeno.toml in " + projectDir
                    + " — run jeno init to create one");
            return 1;
        }

        String name = project.name();
        String version = project.version();
        String tag = "v" + version;
        Path tar = projectDir.resolve(DIST).resolve(name + "-" + version + ".tar.gz");
        if (!Files.isRegularFile(tar)) {
            System.err.println(Ui.errPrefix() + tar + " is missing — run jeno bundle first");
            return 1;
        }

        int jdk = project.javaVersion() != null
                ? project.javaVersion() : Runtime.version().feature();
        String sha;
        try {
            sha = ArtifactStore.sha256(tar);
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + "cannot read " + tar + " — " + e.getMessage());
            return 1;
        }

        ReleaseSigner.Preview signing;
        try {
            signing = ReleaseSigner.prepare(allowUnsigned);
        } catch (ReleaseSigner.SigningException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }

        Plan plan;
        try {
            plan = plan(target, name, version, tag, project);
        } catch (IllegalArgumentException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        boolean network = plan.target != null
                && (plan.repository == null || !plan.repository.isLocal());
        if (ArtifactStore.isOffline() && network) {
            System.err.println(Ui.errPrefix() + "release to " + target
                    + " requires network access — drop --offline or use a local target");
            return 2;
        }

        if (plan.repository != null && plan.repository.isPlainHttp()
                && !plan.repository.isLoopbackNetwork()) {
            System.err.println("warning: releasing over plain HTTP ("
                    + plan.repository.declaration() + ") — the traffic and JENO_PUBLISH_TOKEN"
                    + " are not encrypted");
        }

        Path releaseDir = projectDir.resolve(RELEASE);
        List<Path> files;
        try {
            Files.createDirectories(releaseDir);
            String tarName = tar.getFileName().toString();
            Path shaFile = releaseDir.resolve(tarName + ".sha256");
            Path setup = releaseDir.resolve("install.sh");
            Path install = releaseDir.resolve("install.ps1");
            Path jsonFile = releaseDir.resolve("release.json");
            List<String> signatureNames = signing.signed()
                    ? List.of("install.sh.minisig", "install.ps1.minisig",
                            "release.json.minisig", tarName + ".minisig")
                    : List.of();
            Files.writeString(shaFile, sha + "\n");
            Files.writeString(setup, bootstrap(name, version, plan.installerUrl, sha,
                    signing));
            Files.writeString(install, windowsInstaller(name, version, jdk,
                    plan.installerUrl, sha));
            Files.writeString(jsonFile, releaseJson(name, version, tarName, sha, jdk,
                    signing, signatureNames));

            List<Path> signatures;
            try {
                signatures = ReleaseSigner.sign(releaseDir,
                        List.of(setup, install, jsonFile, tar), signing);
            } catch (ReleaseSigner.SigningException e) {
                System.err.println(Ui.errPrefix() + e.getMessage());
                return 1;
            }
            List<Path> all = new ArrayList<>(List.of(setup, install, shaFile, jsonFile));
            all.addAll(signatures);
            if (signing.signed()) {
                Path pub = releaseDir.resolve("minisign.pub");
                Files.writeString(pub, "untrusted comment: minisign public key\n"
                        + signing.publicKey() + "\n");
                all.add(pub);
            }
            files = List.copyOf(all);
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot prepare " + releaseDir + " — " + e.getMessage());
            return 1;
        }

        System.out.println("release files in " + releaseDir + ":");
        System.out.println("  " + tar);
        for (Path file : files) {
            System.out.println("  " + file);
        }

        if (plan.target == null) {
            System.out.println("upload these four files together, then share the install.sh;");
            System.out.println("  bootstrap installer URL: " + plan.installerUrl);
            return 0;
        }

        String notes;
        try {
            notes = parsed.notesFile() == null ? null
                    : Files.readString(parsed.notesFile());
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot read notes file "
                    + parsed.notesFile() + " — " + e.getMessage());
            return 1;
        }
        return plan.repository == null
                ? publishToGitHub(plan, tar, files, name, version, tag, sha, signing, notes)
                : publishToRepository(plan, tar, files, sha, signing);
    }

    private record Parsed(String target, boolean allowUnsigned, Path notesFile) {
        boolean offlineConflict(ReleasePlan plan) {
            if (!ArtifactStore.isOffline() || plan.plan().target == null) {
                return false;
            }
            return plan.plan().repository == null || !plan.plan().repository.isLocal();
        }
    }

    private static Parsed parse(List<String> args) {
        boolean allowUnsigned = false;
        String target = null;
        Path notesFile = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--dry-run")) {
                // accepted here for the wrapper; planning already knows
            } else if (arg.equals("--allow-unsigned")) {
                allowUnsigned = true;
            } else if (arg.equals("--notes-file")) {
                if (++i >= args.size() || args.get(i).isBlank()) {
                    throw new IllegalArgumentException("--notes-file needs a path");
                }
                notesFile = Path.of(args.get(i));
            } else if (arg.startsWith("-")) {
                throw new IllegalArgumentException("release takes [target], --dry-run,"
                        + " --allow-unsigned and --notes-file — got '" + arg + "'");
            } else if (target == null) {
                target = arg;
            } else {
                throw new IllegalArgumentException("release takes at most one target — got '" + arg + "'");
            }
        }
        Parsed parsed = new Parsed(target, allowUnsigned, notesFile);
        validateNotes(parsed);
        return parsed;
    }

    private static void validateNotes(Parsed parsed) {
        if (parsed.notesFile() == null) {
            return;
        }
        if (parsed.target() == null || !parsed.target().startsWith("gh:")) {
            throw new IllegalArgumentException("--notes-file is only used with a gh: target");
        }
        if (!Files.isRegularFile(parsed.notesFile())) {
            throw new IllegalArgumentException("notes file not found: " + parsed.notesFile());
        }
    }

    private int error(boolean json, boolean dryRun, int exit, String code, String message) {
        if (json) {
            JsonResult.error("release", exit, code, message).dryRun(dryRun).emit(exit);
        } else {
            System.err.println(Ui.errPrefix() + message);
        }
        return exit;
    }

    // ------------------------------------------------------------------
    // JSON / dry-run planning
    // ------------------------------------------------------------------

    private ReleasePlan planRelease(Path moduleDir, String target, boolean allowUnsigned,
                                    boolean json) {
        if (!json) {
            return releasePlan(moduleDir, target, allowUnsigned);
        }
        ReleasePlan[] holder = new ReleasePlan[1];
        JsonCapture.run(() -> {
            holder[0] = releasePlan(moduleDir, target, allowUnsigned);
            return 0;
        });
        return holder[0];
    }

    private record ReleasePlan(Path moduleDir, JenoProject project, Plan plan, Path tar,
                               String sha, int jdk, ReleaseSigner.Preview signing,
                               Path releaseDir, List<PlannedReleaseFile> files,
                               boolean insecureHttp) {}

    private record PlannedReleaseFile(String kind, Path path, boolean wouldWrite,
                                      String sha256, long bytes) {}

    private ReleasePlan releasePlan(Path moduleDir, String target, boolean allowUnsigned) {
        JenoProject project;
        try {
            project = ProjectReader.parse(Files.readString(moduleDir.resolve("jeno.toml")));
        } catch (IOException e) {
            throw new IllegalStateException("no jeno.toml in " + moduleDir
                    + " — run jeno init to create one");
        }
        String name = project.name();
        String version = project.version();
        String tag = "v" + version;
        Path tar = moduleDir.resolve(DIST).resolve(name + "-" + version + ".tar.gz");
        if (!Files.isRegularFile(tar)) {
            throw new IllegalStateException(tar + " is missing — run jeno bundle first");
        }
        String sha = ArtifactStore.sha256(tar);
        ReleaseSigner.Preview signing;
        try {
            signing = ReleaseSigner.prepare(allowUnsigned);
        } catch (ReleaseSigner.SigningException e) {
            throw new IllegalStateException(e.getMessage());
        }
        Plan plan = plan(target, name, version, tag, project);
        Path releaseDir = moduleDir.resolve(RELEASE);
        String tarName = tar.getFileName().toString();
        List<PlannedReleaseFile> files = new ArrayList<>();
        files.add(planned("tar.gz", tar, false, sha));
        files.add(planned("sha256", releaseDir.resolve(tarName + ".sha256"), true, null));
        files.add(planned("setup", releaseDir.resolve("install.sh"), true, null));
        files.add(planned("windows-installer", releaseDir.resolve("install.ps1"), true, null));
        files.add(planned("release-json", releaseDir.resolve("release.json"), true, null));
        if (signing.signed()) {
            files.add(planned("signature", releaseDir.resolve("install.sh.minisig"), true, null));
            files.add(planned("signature", releaseDir.resolve("install.ps1.minisig"), true, null));
            files.add(planned("signature", releaseDir.resolve("release.json.minisig"), true, null));
            files.add(planned("signature", releaseDir.resolve(tarName + ".minisig"), true, null));
            files.add(planned("public-key", releaseDir.resolve("minisign.pub"), true, null));
        }
        boolean insecureHttp = plan.repository != null && plan.repository.isPlainHttp()
                && !plan.repository.isLoopbackNetwork();
        return new ReleasePlan(moduleDir, project, plan, tar, sha,
                project.javaVersion() != null ? project.javaVersion() : Runtime.version().feature(),
                signing, releaseDir, List.copyOf(files), insecureHttp);
    }

    private static PlannedReleaseFile planned(String kind, Path path, boolean wouldWrite,
                                              String sha256) {
        boolean exists = Files.isRegularFile(path);
        return new PlannedReleaseFile(kind, path, wouldWrite && !exists,
                sha256 != null ? sha256 : exists ? ArtifactStore.sha256(path) : null,
                exists ? sizeOf(path) : 0);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private void emitPlan(ReleasePlan plan, boolean dryRun) {
        if (dryRun && plan.insecureHttp()) {
            System.err.println("warning: releasing over plain HTTP ("
                    + plan.plan().target + ") — the traffic and JENO_PUBLISH_TOKEN"
                    + " are not encrypted");
        }
        if (dryRun) {
            if (plan.plan().target == null) {
                System.out.println("dry run — would prepare " + plan.releaseDir() + ":");
            } else {
                System.out.println("dry run — would upload:");
                System.out.println("  " + plan.plan().installerUrl);
                for (String url : plan.plan().auxiliaryUrls) {
                    System.out.println("  " + url);
                }
            }
            for (PlannedReleaseFile file : plan.files()) {
                if (file.wouldWrite()) {
                    System.out.println("  " + file.path() + " (would write)");
                }
            }
            return;
        }
        System.out.println(Ui.ok("released " + plan.project().name() + "@"
                + plan.project().version()));
    }

    private void emitJson(ReleasePlan plan, boolean dryRun) {
        JsonArray files = JsonUtils.array();
        for (PlannedReleaseFile file : plan.files()) {
            JsonObject entry = JsonUtils.object();
            entry.put("kind", file.kind());
            entry.put("path", file.path().toAbsolutePath().normalize().toString());
            entry.put("wouldWrite", dryRun && file.wouldWrite());
            if (file.sha256() != null) {
                entry.put("sha256", file.sha256());
            }
            entry.put("bytes", file.bytes());
            files.add(entry);
        }
        JsonObject project = JsonUtils.object();
        project.put("name", plan.project().name());
        project.put("version", plan.project().version());
        JsonObject signing = JsonUtils.object();
        signing.put("signed", plan.signing().signed());
        if (plan.signing().publicKey() != null) {
            signing.put("publicKey", plan.signing().publicKey());
        }
        JsonArray wouldUpload = JsonUtils.array();
        if (plan.plan().target != null) {
            wouldUpload.add(plan.plan().installerUrl);
            plan.plan().auxiliaryUrls.forEach(wouldUpload::add);
        }
        boolean uploaded = !dryRun && plan.plan().target != null;
        JsonResult result = JsonResult.start("release")
                .workspace(plan.moduleDir(), "single")
                .put("dryRun", dryRun)
                .put("target", plan.plan().target)
                .put("project", project)
                .put("jdk", plan.jdk())
                .put("signing", signing)
                .put("installerUrl", plan.plan().installerUrl)
                .put("bootstrapUrl", plan.plan().bootstrapUrl)
                .put("windowsUrl", plan.plan().windowsUrl)
                .put("files", files)
                .put("wouldUpload", wouldUpload)
                .put("uploaded", uploaded)
                .put("verified", uploaded && plan.plan().repository != null);
        if (plan.insecureHttp()) {
            result.diagnostic("warning", "PLAIN_HTTP_INSECURE",
                    "releasing over plain HTTP (" + plan.plan().target + ") — the traffic and"
                            + " JENO_PUBLISH_TOKEN are not encrypted", List.of());
        }
        result.emit(0);
    }

    // ------------------------------------------------------------------
    // targets
    // ------------------------------------------------------------------

    private record Plan(Repository repository, String target, String name, String version,
                        String installerUrl, String bootstrapUrl, String windowsUrl,
                        List<String> auxiliaryUrls) {}

    private Plan plan(String target, String name, String version, String tag, JenoProject project) {
        if (target == null) {
            Path tar = projectDir.resolve(DIST).resolve(name + "-" + version + ".tar.gz");
            return new Plan(null, null, name, version, tar.toUri().toString(), null, null,
                    List.of());
        }
        if (target.startsWith("gh:")) {
            String origin = target.substring("gh:".length());
            int slash = origin.indexOf('/');
            if (slash <= 0 || slash == origin.length() - 1) {
                throw new IllegalArgumentException("gh: target is gh:<owner>/<repo> — got '"
                        + target + "'");
            }
            String owner = origin.substring(0, slash);
            String repo = origin.substring(slash + 1);
            String base = ArtifactLocations.githubHost() + "/" + owner + "/" + repo
                    + "/releases/download/" + tag;
            String stable = ArtifactLocations.githubHost() + "/" + owner + "/" + repo
                    + "/releases/latest/download/install.sh";
            String windows = ArtifactLocations.githubHost() + "/" + owner + "/" + repo
                    + "/releases/latest/download/install.ps1";
            String tar = base + "/" + name + "-" + version + ".tar.gz";
            List<String> urls = List.of(base + "/install.sh", tar + ".sha256",
                    base + "/release.json", base + "/install.ps1");
            return new Plan(null, target, name, version, tar, stable, windows, urls);
        }

        Repository repository = Repository.parse(target);
        if (repository.layout() != Repository.Layout.JENO) {
            throw new IllegalArgumentException("release publishes installers, not Maven "
                    + "artifacts — use gh:<owner>/<repo>, jeno:https://host/,"
                    + " jeno:http://<private-ip>/ (LAN only) or jeno:local:<dir>");
        }
        String root = repository.isLocal()
                ? repository.directory(ArtifactLocations.projectRoot()).toUri().toString()
                : repository.networkBase();
        if (!root.endsWith("/")) {
            root = root + "/";
        }
        String versioned = root + name + "/" + version + "/";
        String stable = root + name + "/install.sh";
        String windows = root + name + "/install.ps1";
        String tar = versioned + "release/" + name + "-" + version + ".tar.gz";
        List<String> urls = List.of(versioned + "release/install.sh", tar + ".sha256",
                versioned + "release/release.json", versioned + "install.sh", stable,
                versioned + "minisign.pub", root + name + "/minisign.pub",
                versioned + "release/install.ps1", windows);
        return new Plan(repository, target, name, version, tar, stable, windows, urls);
    }

    // ------------------------------------------------------------------
    // upload + read-back
    // ------------------------------------------------------------------

    private int publishToRepository(Plan plan, Path tar, List<Path> files, String sha,
                                    ReleaseSigner.Preview signing) {
        List<String> urls = new ArrayList<>();
        urls.add(plan.installerUrl);
        urls.addAll(plan.auxiliaryUrls.subList(0, 2 + 1));
        try {
            if (plan.repository.isLocal()) {
                Path versioned = localDirectory(plan).resolve("release");
                Files.createDirectories(versioned);
                writeAtomically(versioned.resolve(tar.getFileName()), Files.readAllBytes(tar));
                for (Path file : files) {
                    writeAtomically(versioned.resolve(file.getFileName()),
                            Files.readAllBytes(file));
                }
                Path setupFile = files.stream().filter(path -> path.getFileName().toString()
                        .equals("install.sh")).findFirst().orElseThrow();
                Path installFile = files.stream().filter(path -> path.getFileName().toString()
                        .equals("install.ps1")).findFirst().orElseThrow();
                writeAtomically(localDirectory(plan).resolve("install.sh"),
                        Files.readAllBytes(setupFile));
                writeAtomically(localDirectory(plan).getParent().resolve("install.sh"),
                        Files.readAllBytes(setupFile));
                writeAtomically(localDirectory(plan).resolve("install.ps1"),
                        Files.readAllBytes(installFile));
                writeAtomically(localDirectory(plan).getParent().resolve("install.ps1"),
                        Files.readAllBytes(installFile));
                if (signing.signed()) {
                    Path pub = files.stream().filter(path -> path.getFileName().toString()
                            .equals("minisign.pub")).findFirst().orElseThrow();
                    writeAtomically(localDirectory(plan).resolve("minisign.pub"),
                            Files.readAllBytes(pub));
                    writeAtomically(localDirectory(plan).getParent().resolve("minisign.pub"),
                            Files.readAllBytes(pub));
                }
            } else {
                JenoUploads.put(plan.installerUrl, Files.readAllBytes(tar));
                System.out.println("  PUT " + plan.installerUrl);
                for (Path file : files) {
                    String url = releaseUrl(plan, file.getFileName().toString());
                    JenoUploads.put(url, Files.readAllBytes(file));
                    System.out.println("  PUT " + url);
                }
                Path setupFile = files.stream().filter(path -> path.getFileName().toString()
                        .equals("install.sh")).findFirst().orElseThrow();
                Path installFile = files.stream().filter(path -> path.getFileName().toString()
                        .equals("install.ps1")).findFirst().orElseThrow();
                JenoUploads.put(plan.bootstrapUrl, Files.readAllBytes(setupFile));
                JenoUploads.put(plan.windowsUrl, Files.readAllBytes(installFile));
                if (signing.signed()) {
                    Path pub = files.stream().filter(path -> path.getFileName().toString()
                            .equals("minisign.pub")).findFirst().orElseThrow();
                    byte[] bytes = Files.readAllBytes(pub);
                    JenoUploads.put(plan.auxiliaryUrls.get(5), bytes);
                    JenoUploads.put(plan.auxiliaryUrls.get(6), bytes);
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println(Ui.errPrefix() + "release upload failed — " + e.getMessage());
            return 1;
        }

        try {
            byte[] readBack = plan.repository.isLocal()
                    ? Files.readAllBytes(localReleaseTar(plan))
                    : JenoUploads.get(plan.installerUrl);
            String actual = ArtifactStore.sha256(readBack);
            if (!actual.equals(sha)) {
                System.err.println(Ui.errPrefix() + "release verification failed for " + plan.installerUrl
                        + " — wrote " + sha + ", read " + actual);
                return 1;
            }
            if (signing.signed()) {
                Path signature = projectDir.resolve(RELEASE)
                        .resolve(tar.getFileName() + ".minisig");
                ReleaseSigner.verify(tar, signature, signing.publicKey());
                if (!plan.repository.isLocal()) {
                    byte[] remoteSignature = JenoUploads.get(plan.installerUrl + ".minisig");
                    Path temp = Files.createTempFile("jeno-remote-", ".minisig");
                    try {
                        Files.write(temp, remoteSignature);
                        ReleaseSigner.verify(tar, temp, signing.publicKey());
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                }
            }
            if (!plan.repository.isLocal()) {
                JenoUploads.get(plan.auxiliaryUrls.get(0));
                JenoUploads.get(plan.auxiliaryUrls.get(2));
                JenoUploads.get(plan.auxiliaryUrls.get(7));
                JenoUploads.get(plan.windowsUrl);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println(Ui.errPrefix() + "release verification failed — " + e.getMessage());
            return 1;
        }

        System.out.println("released " + plan.target + "; installer:");
        System.out.println("  curl -fsSL " + plan.bootstrapUrl + " | bash");
        return 0;
    }

    private int publishToGitHub(Plan plan, Path tar, List<Path> files,
                                String name, String version, String tag, String sha,
                                ReleaseSigner.Preview signing, String notes) {
        String token = GitHubReleases.token();
        if (token == null || token.isBlank()) {
            System.err.println(Ui.errPrefix() + "JENO_GITHUB_TOKEN environment variable is not set — "
                    + "set it to your GitHub personal access token, or run with --dry-run");
            return 1;
        }
        String origin = "gh:" + plan.target.substring("gh:".length());
        String owner = origin.substring("gh:".length(), origin.indexOf('/'));
        String repo = origin.substring(origin.indexOf('/') + 1);

        String releaseId;
        String tarDownload;
        try {
            var body = JsonUtils.object();
            body.put("tag_name", tag);
            body.put("name", tag);
            body.put("body", notes == null ? "released with jeno release" : notes);
            releaseId = GitHubReleases.create(origin, owner, repo, tag, token,
                    body.toString());
            tarDownload = null;
            for (Path file : concat(tar, files)) {
                String download = GitHubReleases.upload(owner, repo, releaseId, file, token);
                if (file.equals(tar)) {
                    tarDownload = download;
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }

        if (tarDownload == null) {
            tarDownload = "https://github.com/" + owner + "/" + repo
                    + "/releases/download/" + tag + "/" + tar.getFileName();
        }
        try {
            byte[] readBack = null;
            long[] backoff = {500, 1000, 2000};
            for (int attempt = 0; attempt <= backoff.length; attempt++) {
                try {
                    readBack = GitHubReleases.download(tarDownload);
                    break;
                } catch (IOException e) {
                    if (attempt == backoff.length) {
                        throw e;
                    }
                    Thread.sleep(backoff[attempt]);
                }
            }
            String actual = ArtifactStore.sha256(readBack);
            if (!actual.equals(sha)) {
                System.err.println(Ui.errPrefix() + "release verification failed for " + tarDownload
                        + " — uploaded " + sha + ", read " + actual);
                return 1;
            }
            if (signing.signed()) {
                Path signature = projectDir.resolve(RELEASE)
                        .resolve(tar.getFileName() + ".minisig");
                Path temp = Files.createTempFile("jeno-gh-", ".tar.gz");
                try {
                    Files.write(temp, readBack);
                    ReleaseSigner.verify(temp, signature, signing.publicKey());
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println(Ui.errPrefix() + "release verification failed for " + tarDownload
                    + " — " + e.getMessage());
            return 1;
        }

        System.out.println("released to " + plan.target + "; installer:");
        System.out.println("  curl -fsSL " + plan.bootstrapUrl + " | bash");
        return 0;
    }

    private static List<Path> concat(Path first, List<Path> rest) {
        List<Path> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private String releaseUrl(Plan plan, String fileName) {
        String base = plan.installerUrl.substring(0, plan.installerUrl.lastIndexOf('/') + 1);
        return base + fileName;
    }

    private Path localDirectory(Plan plan) {
        return plan.repository().directory(ArtifactLocations.projectRoot())
                .resolve(plan.name()).resolve(plan.version());
    }

    private Path localReleaseTar(Plan plan) {
        return localDirectory(plan).resolve("release")
                .resolve(plan.name() + "-" + plan.version() + ".tar.gz");
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".jeno-release-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ------------------------------------------------------------------
    // generated files
    // ------------------------------------------------------------------

    private static String windowsInstaller(String name, String version, int jdk,
                                           String url, String sha) {
        return """
            # jeno %1$s %2$s Windows installer (PowerShell 5+).
            # This script verifies sha256 only; Windows has no minisign dependency.
            $ErrorActionPreference = "Stop"
            $Name = "%1$s"
            $Version = "%2$s"
            $Jdk = %3$d
            $Url = "%4$s"
            $Sha = "%5$s"

            $Java = if ($env:JAVA_HOME) {
                Join-Path $env:JAVA_HOME "bin\\java.exe"
            } else {
                (Get-Command java.exe -ErrorAction Stop).Source
            }
            $VersionText = & $Java -version 2>&1 | Select-Object -First 1
            if ($VersionText -match '"([0-9]+)') {
                $Major = [int]$Matches[1]
            } else {
                throw "$Name needs JDK $Jdk or newer; cannot read the Java version"
            }
            if ($Major -lt $Jdk) {
                throw "$Name needs JDK $Jdk or newer (found $Major)"
            }

            $Tmp = Join-Path ([IO.Path]::GetTempPath()) ("jeno-install-" + [guid]::NewGuid())
            New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
            try {
                $Tar = Join-Path $Tmp "pkg.tar.gz"
                if ($Url -like "file:*") {
                    Copy-Item ([Uri]$Url).LocalPath $Tar -Force
                } else {
                    Invoke-WebRequest -Uri $Url -OutFile $Tar -UseBasicParsing
                }
                $Hash = (Get-FileHash -Algorithm SHA256 -Path $Tar).Hash.ToLowerInvariant()
                if ($Hash -ne $Sha.ToLowerInvariant()) {
                    throw "$Name: sha256 mismatch — expected $Sha, got $Hash"
                }
                $TarExe = (Get-Command tar.exe -ErrorAction Stop).Source
                & $TarExe -xzf $Tar -C $Tmp
                if ($LASTEXITCODE -ne 0) {
                    throw "$Name: tar.exe could not extract the installer"
                }

                $Src = Join-Path $Tmp "$Name-$Version"
                $Install = "$env:USERPROFILE\\.$Name"
                $Lib = Join-Path $Install "lib"
                $Bin = Join-Path $Install "bin"
                New-Item -ItemType Directory -Force -Path $Lib, $Bin | Out-Null
                $OldToolchain = Join-Path $Lib "toolchain"
                if (Test-Path $OldToolchain) {
                    Remove-Item -Recurse -Force $OldToolchain
                }
                $SrcLib = Join-Path $Src "lib"
                Get-ChildItem $SrcLib -Force | Copy-Item -Destination $Lib -Recurse -Force
                $SrcBin = Join-Path $Src "bin"
                Copy-Item (Join-Path $SrcBin "$Name.cmd") (Join-Path $Bin "$Name.cmd") -Force

                $Uninstall = Join-Path $Install "uninstall.ps1"
                @'
            $InstallDir = "$env:USERPROFILE\\.%1$s"
            Remove-Item -Recurse -Force $InstallDir -ErrorAction SilentlyContinue
            $UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
            if ($UserPath) {
                $Bin = Join-Path $InstallDir "bin"
                $Next = (($UserPath -split ";") | Where-Object { $_ -and $_ -ne $Bin }) -join ";"
                [Environment]::SetEnvironmentVariable("Path", $Next, "User")
            }
            '@ | Set-Content -Path $Uninstall -Encoding ASCII

                $UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
                if ([string]::IsNullOrWhiteSpace($UserPath)) {
                    $UserPath = $Bin
                } elseif (($UserPath -split ";") -notcontains $Bin) {
                    $UserPath = "$UserPath;$Bin"
                }
                [Environment]::SetEnvironmentVariable("Path", $UserPath, "User")
                Write-Host "installed $Name $Version to $Install"
            } catch {
                throw
            } finally {
                Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
            }
            """.formatted(name, version, jdk, url, sha);
    }

    private static String bootstrap(String name, String version, String url, String sha,
                                    ReleaseSigner.Preview signing) {
        return """
            #!/bin/sh
            # %1$s %2$s installer: download, verify, unpack, then run the bundled setup.
            set -e

            URL="%3$s"
            SHA="%4$s"
            TMP=$(mktemp -d)
            trap 'rm -rf "$TMP"' EXIT

            if command -v curl >/dev/null 2>&1; then
                curl -fsSL "$URL" -o "$TMP/pkg.tar.gz"
            elif command -v wget >/dev/null 2>&1; then
                wget -q "$URL" -O "$TMP/pkg.tar.gz"
            else
                echo "%1$s: need curl or wget" >&2
                exit 1
            fi

            if command -v sha256sum >/dev/null 2>&1; then
                echo "$SHA  $TMP/pkg.tar.gz" | sha256sum -c - >/dev/null
            elif command -v shasum >/dev/null 2>&1; then
                echo "$SHA  $TMP/pkg.tar.gz" | shasum -a 256 -c - >/dev/null
            else
                echo "%1$s: need sha256sum or shasum" >&2
                exit 1
            fi

            %5$s
            tar -xzf "$TMP/pkg.tar.gz" -C "$TMP"
            "$TMP/%1$s-%2$s/install.sh"
            """.formatted(name, version, url, sha,
                signing.signed() ? """
            MINISIGN="$(command -v minisign || true)"
            if [ -z "$MINISIGN" ]; then
                echo "%1$s: this release is signed — install minisign to verify it" >&2
                exit 1
            fi
            if command -v curl >/dev/null 2>&1; then
                curl -fsSL "$URL.minisig" -o "$TMP/pkg.tar.minisig"
            else
                wget -q "$URL.minisig" -O "$TMP/pkg.tar.minisig"
            fi
            "$MINISIGN" -V -q -P "%2$s" -m "$TMP/pkg.tar.gz" -x "$TMP/pkg.tar.minisig"
            """.formatted(name, signing.publicKey())
                        : """
            echo "warning: this release is unsigned — only sha256 was checked" >&2
            """);
    }

    private static String releaseJson(String name, String version, String artifact, String sha,
                                      int jdk, ReleaseSigner.Preview signing,
                                      List<String> signatureNames) {
        var root = JsonUtils.object();
        root.put("name", name);
        root.put("version", version);
        root.put("artifact", artifact);
        root.put("sha256", sha);
        root.put("jdk", jdk);
        root.put("jeno", Jeno.VERSION);
        root.put("platform", "any");
        var installers = JsonUtils.object();
        installers.put("unix", "install.sh");
        installers.put("windows", "install.ps1");
        root.put("installers", installers);
        root.put("signed", signing.signed());
        if (signing.signed()) {
            root.put("minisign.publicKey", signing.publicKey());
        }
        var signatures = JsonUtils.array();
        signatureNames.forEach(signatures::add);
        root.put("signatures", signatures);
        return root.toString();
    }
}
