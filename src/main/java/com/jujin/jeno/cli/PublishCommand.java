package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.Repository;
import com.jujin.jeno.project.Species;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.project.Requirement;
import com.jujin.jeno.resolve.ArtifactLocations;
import com.jujin.jeno.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jeno publish [target|repo-name]} — the decentralized release (DESIGN.md §7.2).
 *
 * <p>Generates the three-piece artifact into target/dist/, then — when a target is
 * given — uploads it via the platform's release API:
 *
 * <pre>
 *   jeno publish                    → target/dist/ + upload hint
 *   jeno publish gh:owner/repo     → target/dist/ + GitHub native upload
 *   jeno publish https://host/     → target/dist/ + jeno layout PUT (no group needed)
 *                                  and GET-read-back verification of all three files
 *   jeno publish http://192.168.1.39:8080/upload/ → same, plain http on a
 *                                 literal private-network address (warning: not encrypted)
 *   jeno publish local:<dir>       → target/dist/ + the same layout on disk
 *   jeno publish <declared-repo>   → target/dist/ + that [repos.<name>] jeno repository
 * </pre>
 *
 * <p>publish only writes the native jeno layout, so a bare https/local target
 * means that layout. Maven is an explicit exception ({@code maven:<declaration>})
 * and is refused: jeno writes no POM. Reading Maven repositories stays
 * supported; only publishing to one is not.
 *
 * <p>Authentication: {@code JENO_GITHUB_TOKEN} for gh: targets, and
 * {@code JENO_PUBLISH_TOKEN} for jeno-layout hosts that want one. Without a
 * token, the files are generated but not uploaded — the checklist is printed
 * instead.
 */
public final class PublishCommand implements Command {

    private static final String CLASSES = "target/classes";

    private final Path projectDir;

    @Inject
    public PublishCommand(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public String summary() {
        return "write the release triple and upload it to gh:, a jeno URL/directory, "
                + "or a declared jeno repository";
    }

    @Override
    public String help() {
        return """
               targets jeno can read back: gh:<owner>/<repo>, https://host/,
               http://<private-ip>/, local:<dir>, or the name of a declared
               [repos.<name>] with type = "jeno"; no target only prepares
               target/dist/. --dry-run validates the plan without writing target/dist
               or uploading. plain http is for literal private addresses only
               (LAN); public hosts need https. a Maven-layout target (a declared
               Maven repository, or
               maven:<declaration>) is refused: jeno writes no POM.
               dependencies from another jeno repository are written into the
               descriptor as absolute https://repo.example/@version facts;
               same-repository dependencies remain bare sibling versions.
               """ + "\n" + RepositoryHelp.INSECURE_TOKEN;
    }
    @Override
    public String usage() {
        return "[target|repo-name] [--dry-run]";
    }


    @Override
    public Command.Category category() {
        return Command.Category.PUBLISH;
    }

    @Override
    public int order() {
        return 10;
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
                .build();
    }

    @Override
    public int execute(List<String> args) {
        ProjectFiles.rejectLegacy(projectDir);
        boolean json = args.contains("--json");
        boolean dryRun = args.contains("--dry-run");
        List<String> cleaned = args.stream()
                .filter(arg -> !arg.equals("--json") && !arg.equals("--dry-run"))
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
        if (moduleArgs.size() > 1) {
            return error(json, dryRun, 2, "USAGE",
                    "publish takes at most one target — got '" + moduleArgs.get(1) + "'");
        }

        String target = moduleArgs.isEmpty() ? null : moduleArgs.getFirst();
        PublishPlan plan;
        try {
            plan = publishPlan(moduleDir, target);
        } catch (RuntimeException e) {
            return error(json, dryRun, 1, "PUBLISH_PLAN_FAILED", e.getMessage());
        }
        if (ArtifactStore.isOffline() && plan.network()) {
            return error(json, dryRun, 2, "OFFLINE_FORBIDDEN",
                    "publish to " + target + " requires network access");
        }
        if (!dryRun) {
            Path targetDir = moduleDir;
            List<String> targetArgs = moduleArgs;
            JsonCapture.Result captured = JsonCapture.run(() ->
                    new PublishCommand(targetDir).executeHuman(targetArgs));
            if (captured.exitCode() != 0) {
                return error(json, dryRun, captured.exitCode(), "PUBLISH_FAILED", captured.message());
            }
            plan = publishPlan(moduleDir, target);
        }

        if (json) {
            emitJson(plan, dryRun);
        } else {
            emitPlan(plan, dryRun);
        }
        return 0;
    }

    private int error(boolean json, boolean dryRun, int exit, String code, String message) {
        if (json) {
            JsonResult.error("publish", exit, code, message).dryRun(dryRun).emit(exit);
        } else {
            System.err.println(Ui.errPrefix() + message);
        }
        return exit;
    }

    private int executeHuman(List<String> args) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new PublishCommand(dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        if (args.size() > 1) {
            System.err.println(Ui.errPrefix() + "publish takes at most one target — got '" + args.get(1) + "'");
            return 2;
        }
        // The upload reads the bytes in target/dist/, so the lock covers the network
        // phase too: locking only the write phase would let clean --dist pull a
        // jar out from under a running upload. The cost is that another writer
        // waits for the upload — on purpose (REMINDS 13).
        return ProjectLock.with(projectDir, () -> publish(args));
    }

    private int publish(List<String> args) {
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
        String jarName = name + "-" + version + ".jar";
        String descriptorName = name + "-" + version + ".jeno.toml";
        String shaName = name + "-" + version + ".sha256";

        Path classes = projectDir.resolve(CLASSES);
        if (!Files.isDirectory(classes)) {
            System.err.println(Ui.errPrefix() + CLASSES + " is missing — run jeno build first");
            return 1;
        }

        String target = args.isEmpty() ? null : args.getFirst();
        // Only targets jeno itself can read back. Maven layouts are refused
        // before anything is written: jeno emits no POM, so neither jeno nor
        // Maven could consume what it just uploaded.
        Repository publishTarget = null;
        if (target != null && !target.startsWith("gh:")) {
            try {
                publishTarget = Repository.resolveJenoTarget(project, target);
            } catch (IllegalArgumentException e) {
                System.err.println(Ui.errPrefix() + e.getMessage());
                return 1;
            }
            if (publishTarget.layout() == Repository.Layout.MAVEN) {
                System.err.println(Ui.errPrefix() + "cannot publish to a Maven-layout target — "
                        + "jeno writes no POM, so neither jeno nor Maven can read it back; "
                        + "use gh:<owner>/<repo>, https://host/, "
                        + "http://<private-ip>/ (or local:<dir>), or a declared "
                        + "type = \"jeno\" repository");
                return 1;
            }
            if (publishTarget.isPlainHttp() && !publishTarget.isLoopbackNetwork()) {
                System.err.println("warning: publishing over plain HTTP ("
                        + publishTarget.location() + ") — the traffic and JENO_PUBLISH_TOKEN"
                        + " are not encrypted");
            }
        }
        boolean networkTarget = target != null
                && (target.startsWith("gh:") || (publishTarget != null && !publishTarget.isLocal()));
        if (ArtifactStore.isOffline() && networkTarget) {
            System.err.println(Ui.errPrefix() + "publish to " + target
                    + " requires network access — drop --offline or use a local target");
            return 2;
        }
        // Same-repository natives stay bare siblings; native dependencies from
        // another network jeno repository are serialized as absolute source
        // facts. Local sources and repository aliases cannot travel in a
        // descriptor.
        String publishBase = publishTarget != null && publishTarget.isJenoLayout()
                ? ArtifactLocations.canonicalLocation(publishTarget) : null;
        java.util.Set<String> siblings = new java.util.HashSet<>();
        for (Requirement requirement : project.requirements()) {
            if (requirement.species() == Species.NAMED) {
                String source = namedSource(project, requirement);
                Repository sourceRepository;
                try {
                    sourceRepository = Repository.parse(source);
                } catch (IllegalArgumentException e) {
                    System.err.println(Ui.errPrefix() + "cannot resolve source of '"
                            + requirement.id() + "' (" + source + ") — " + e.getMessage());
                    return 1;
                }
                if (publishBase != null
                        && publishBase.equals(ArtifactLocations.canonicalLocation(sourceRepository))) {
                    siblings.add(requirement.id());
                    continue;
                }
                // A local source cannot travel in a descriptor: a descriptor must
                // be replayable on a machine that does not have this checkout.
                if (sourceRepository.transport() != Repository.Transport.NETWORK) {
                    System.err.println(Ui.errPrefix() + "'" + requirement.id()
                            + "' is a local jeno source (" + source + ") — a descriptor "
                            + "cannot carry a filesystem path; publish it to a network jeno "
                            + "repository or keep it in the same repository as the artifact");
                    return 1;
                }
                // Network native sources are portable facts. descriptor() will
                // emit an absolute source when it is not a sibling of the target.
                continue;
            }
            if (requirement.repository() == null) {
                continue;
            }
            // A named Maven repository cannot be serialized into a descriptor
            // without exporting a repository alias. Keep the old safety refusal.
            Repository declared = project.repos().get(requirement.repository());
            String where = declared == null ? "unknown" : ArtifactLocations.canonicalLocation(declared);
            System.err.println(Ui.errPrefix() + "'" + requirement.id()
                    + "' belongs to Maven repository '" + requirement.repository() + "' ("
                    + where + ") — a descriptor cannot carry that repository alias. Move the "
                    + "dependency to Central or publish a jeno-native source");
            return 1;
        }

        if (!Files.isDirectory(projectDir.resolve("target/dist"))
                && DistOutput.legacyPresent(projectDir)) {
            System.err.println(Ui.errPrefix() + "deliverables moved to target/dist — the old dist/ is "
                    + "no longer read; rerun jeno package (or bundle) to write the new location");
            return 1;
        }

        Path dist;
        try {
            dist = DistOutput.dist(projectDir);
            // clear only what publish writes. The jar belongs to `jeno package`
            // — publishing does not regenerate it, and deleting it here would
            // make the two commands fight over the same path.
            for (String file : List.of(descriptorName, shaName)) {
                DistOutput.clear(dist.resolve(file));
            }
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot prepare target/dist/: " + e.getMessage());
            return 1;
        }

        // publish uploads what package built — it never builds its own jar.
        // Building one here would silently overwrite jeno package's output with a
        // different (manifest-less) jar, and a release would then carry bytes
        // nobody reviewed.
        Path jarFile = dist.resolve(jarName);
        if (!Files.exists(jarFile)) {
            System.err.println(Ui.errPrefix() + jarFile + " is missing — run jeno package first "
                    + "(publish uploads what package builds; it does not build its own jar)");
            return 1;
        }

        try {
            Files.writeString(dist.resolve(descriptorName), descriptor(project, siblings));
            Files.writeString(dist.resolve(shaName),
                    ArtifactStore.sha256(jarFile) + "\n");
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot write the descriptor or sha256 into target/dist/");
            return 1;
        }

        System.out.println(Ui.heading("published the release triple into target/dist/:"));
        System.out.println("  " + jarFile);
        System.out.println("  " + dist.resolve(descriptorName));
        System.out.println("  " + dist.resolve(shaName));

        if (args.isEmpty()) {
            System.out.println("upload the three files to a GitHub release or a jeno-native "
                    + "repository, then tell a teammate:");
            System.out.println("  jeno add \"" + name + "\" gh:<owner>/" + name + "@v" + version);
            System.out.println("  (or publish https://host/ — or"
                    + " http://<private-ip>/ on a LAN — to host it yourself)");
            return 0;
        }

        // target given — only the jeno layout is allowed by this point
        if (publishTarget != null) {
            return publishTarget.isLocal()
                    ? publishToLocalJenoRepository(publishTarget, project, dist,
                            jarName, descriptorName, shaName)
                    : uploadToJenoRepository(publishTarget, project, dist,
                            jarName, descriptorName, shaName);
        }

        String token = GitHubReleases.token();
        if (token == null || token.isBlank()) {
            System.err.println(Ui.errPrefix() + "JENO_GITHUB_TOKEN environment variable is not set — "
                    + "set it to your GitHub personal access token, or run without the "
                    + "target to get the upload checklist");
            return 1;
        }

        System.out.println(Ui.dim("uploading to " + target + " …"));
        return upload(target, token, project, dist, jarName, descriptorName, shaName);
    }

    // ------------------------------------------------------------------
    // jeno-layout uploads — HTTP PUT or a local directory
    // ------------------------------------------------------------------

    /**
     * Publishes the triple to an jeno-layout repository over HTTP:
     * {@code <base>/<name>/<version>/<name>-<version>.{jar,jeno.toml,sha256}}.
     * The same two-level layout {@code [repos]} reads for an {@code jeno:}
     * entry, so a library published here needs no platform, account or registry
     * — and no {@code [project].group}.
     *
     * <p>Upload is HTTP PUT (WebDAV/object-store semantics), one file per
     * request, followed by GET-read-back verification. PUT is deliberate: the
     * jeno layout gives each byte a final deterministic URL, PUT is idempotent,
     * and a retry cannot create a duplicate. POST is for server-assigned
     * upload endpoints and multipart forms; using it here would need a
     * host-specific protocol and would make the published layout unknowable.
     * A POST-only host can front the repository with a PUT-capable gateway.
     *
     * <p>A host that accepts PUT is enough (nginx with dav_module, Apache
     * mod_dav, most object-storage gateways); {@code JENO_PUBLISH_TOKEN}, when
     * set, is sent as a bearer token. jeno speaks exactly one upload protocol on
     * purpose — and it writes no POM, which is why a Maven-layout target is not
     * accepted at all.
     */
    private int uploadToJenoRepository(Repository repository, JenoProject project, Path dist,
                                      String jarName, String descriptorName, String shaName) {
        String target = repository.location();
        String root = repository.networkBase();
        String directory = project.name() + "/" + project.version() + "/";

        List<Path> files = List.of(
                dist.resolve(jarName),
                dist.resolve(descriptorName),
                dist.resolve(shaName));
        for (Path file : files) {
            String url = root + directory + file.getFileName();
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
                JenoUploads.put(url, contentType(file), bytes, repository.insecure());
            } catch (IOException | InterruptedException e) {
                System.err.println(Ui.errPrefix() + "PUT " + url + " failed — " + e.getMessage());
                System.err.println("  jeno uploads to the final artifact URL with HTTP PUT because "
                        + "the jeno layout is deterministic and retries must be idempotent.");
                System.err.println("  the host must accept HTTP PUT (WebDAV/object-store style); "
                        + "a POST-only upload form is not enough.");
                return 1;
            }
            try {
                byte[] readBack = JenoUploads.get(url);
                if (!java.util.Arrays.equals(bytes, readBack)) {
                    System.err.println(Ui.errPrefix() + "uploaded " + url
                            + " but read-back returned different bytes — the repository is not "
                            + "serving what was written");
                    System.err.println("  check that the host stores the exact PUT body and that "
                            + "no CDN is serving a stale representation");
                    return 1;
                }
            } catch (IOException | InterruptedException e) {
                System.err.println(Ui.errPrefix() + "uploaded " + url
                        + " but could not read it back — " + e.getMessage());
                System.err.println("  an jeno repository must be anonymously readable; fix the "
                        + "read path and re-run publish");
                return 1;
            }
            System.out.println("  " + Ui.ok("PUT " + url + " (verified)"));
        }

        updateIndex(repository, project.name(), project.version());

        System.out.println(Ui.ok("published to " + target + " — consume it with:"));
        printConsumeHint(repository, project);
        return 0;
    }

    private int publishToLocalJenoRepository(Repository repository, JenoProject project, Path dist,
                                            String jarName, String descriptorName,
                                            String shaName) {
        String target = repository.location();
        Path directory;
        try {
            directory = ArtifactLocations.localDirectory(repository)
                    .resolve(project.name()).resolve(project.version());
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + "cannot resolve " + target + " — " + e.getMessage());
            return 1;
        }

        List<Path> files = List.of(
                dist.resolve(jarName),
                dist.resolve(descriptorName),
                dist.resolve(shaName));
        try {
            Files.createDirectories(directory);
            for (Path file : files) {
                writeAtomically(directory.resolve(file.getFileName()),
                        Files.readAllBytes(file));
            }
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot write " + directory + " — " + e.getMessage());
            return 1;
        }
        for (Path file : files) {
            System.out.println("  " + directory.resolve(file.getFileName()));
        }

        updateIndex(repository, project.name(), project.version());

        System.out.println(Ui.ok("published to " + target + " — consume it with:"));
        printConsumeHint(repository, project);
        return 0;
    }

    /**
     * Updates the per-artifact discovery index after the artifact triple is
     * successfully present and readable. The index is discovery metadata, not a
     * build fact: an unavailable or malformed index is reported as a warning,
     * but the already-published artifact remains a success.
     */
    private static void updateIndex(Repository repository, String name, String version) {
        try {
            JenoIndex.Index index = JenoIndex.update(repository, name, version);
            System.out.println("  " + Ui.ok("index updated: " + JenoIndex.FILE + " ("
                    + index.versions().size() + " version(s))"));
        } catch (RuntimeException e) {
            System.err.println(Ui.warn("artifact published, but " + JenoIndex.FILE
                    + " was not updated — " + e.getMessage()));
        }
    }

    /** Writes beside the target and moves it into place, so a reader never sees half a file. */
    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".jeno-publish-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** The content type of one jeno-layout artifact. */
    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".jar")) {
            return "application/java-archive";
        }
        if (name.endsWith(".jeno.toml")) {
            return "application/toml; charset=utf-8";
        }
        if (name.endsWith(".sha256")) {
            return "text/plain; charset=utf-8";
        }
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------
    // JSON / dry-run planning
    // ------------------------------------------------------------------

    private record PublishPlan(Path projectDir, JenoProject project, String target,
                               Path dist, List<PlannedArtifact> artifacts,
                               boolean network, boolean insecureHttp) {}

    private record PlannedArtifact(String kind, Path path, String sha256, long bytes,
                                   String url, boolean wouldWrite, boolean wouldUpload) {}

    private PublishPlan publishPlan(Path moduleDir, String target) {
        JenoProject project;
        try {
            project = ProjectReader.parse(Files.readString(moduleDir.resolve("jeno.toml")));
        } catch (IOException e) {
            throw new IllegalStateException("no jeno.toml in " + moduleDir
                    + " — run jeno init to create one");
        }
        if (!Files.isDirectory(moduleDir.resolve(CLASSES))) {
            throw new IllegalStateException(CLASSES + " is missing — run jeno build first");
        }

        Repository repository = null;
        boolean network = false;
        if (target != null) {
            if (target.startsWith("gh:")) {
                network = true;
            } else {
                repository = Repository.resolveJenoTarget(project, target);
                if (repository.layout() == Repository.Layout.MAVEN) {
                    throw new IllegalStateException("cannot publish to a Maven-layout target — "
                            + "jeno writes no POM; use gh:<owner>/<repo>, https://host/, "
                            + "or local:<dir>");
                }
                network = !repository.isLocal();
            }
        }

        String jarName = project.name() + "-" + project.version() + ".jar";
        String descriptorName = project.name() + "-" + project.version() + ".jeno.toml";
        String shaName = project.name() + "-" + project.version() + ".sha256";
        Path dist = moduleDir.resolve("target/dist");
        Path jar = dist.resolve(jarName);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(jar + " is missing — run jeno package first");
        }

        List<PlannedArtifact> artifacts = new ArrayList<>();
        artifacts.add(plannedArtifact("jar", jar, target, project, repository, false, false));
        Path descriptor = dist.resolve(descriptorName);
        Path sha = dist.resolve(shaName);
        boolean descriptorExists = Files.isRegularFile(descriptor);
        boolean shaExists = Files.isRegularFile(sha);
        artifacts.add(new PlannedArtifact("descriptor", descriptor,
                descriptorExists ? ArtifactStore.sha256(descriptor) : null,
                descriptorExists ? sizeOf(descriptor) : 0,
                artifactUrl(target, repository, project, descriptorName),
                !descriptorExists, target != null));
        artifacts.add(new PlannedArtifact("sha256", sha,
                shaExists ? ArtifactStore.sha256(sha) : null,
                shaExists ? sizeOf(sha) : 0,
                artifactUrl(target, repository, project, shaName),
                !shaExists, target != null));
        boolean insecureHttp = repository != null && repository.isPlainHttp()
                && !repository.isLoopbackNetwork();
        return new PublishPlan(moduleDir, project, target, dist,
                List.copyOf(artifacts), network, insecureHttp);
    }

    private static PlannedArtifact plannedArtifact(String kind, Path path, String target,
                                                   JenoProject project,
                                                   Repository repository,
                                                   boolean wouldWrite,
                                                   boolean wouldUpload) {
        return new PlannedArtifact(kind, path, ArtifactStore.sha256(path), sizeOf(path),
                artifactUrl(target, repository, project, path.getFileName().toString()),
                wouldWrite, wouldUpload || target != null);
    }

    private static String artifactUrl(String target, Repository repository,
                                      JenoProject project, String fileName) {
        if (target == null) {
            return null;
        }
        if (target.startsWith("gh:")) {
            String origin = target.substring("gh:".length());
            int slash = origin.indexOf('/');
            String owner = origin.substring(0, slash);
            String repo = origin.substring(slash + 1);
            return ArtifactLocations.githubHost() + "/" + owner + "/" + repo
                    + "/releases/download/v" + project.version() + "/" + fileName;
        }
        if (repository.isLocal()) {
            return ArtifactLocations.localDirectory(repository)
                    .resolve(project.name()).resolve(project.version())
                    .resolve(fileName).toUri().toString();
        }
        String base = repository.networkBase();
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base + project.name() + "/" + project.version() + "/" + fileName;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private void emitPlan(PublishPlan plan, boolean dryRun) {
        if (dryRun && plan.insecureHttp()) {
            System.err.println("warning: publishing over plain HTTP ("
                    + plan.target() + ") — the traffic and JENO_PUBLISH_TOKEN"
                    + " are not encrypted");
        }
        if (dryRun) {
            System.out.println("dry run — would publish "
                    + (plan.target() == null ? "target/dist/" : plan.target()) + ":");
        } else {
            System.out.println(Ui.ok("published " + plan.project().name() + "@"
                    + plan.project().version()
                    + (plan.target() == null ? " into target/dist/" : " to " + plan.target())));
        }
        for (PlannedArtifact artifact : plan.artifacts()) {
            System.out.println("  " + artifact.path()
                    + (artifact.wouldWrite() ? " (would write)" : "")
                    + (artifact.url() == null ? "" : " → " + artifact.url()));
        }
    }

    private void emitJson(PublishPlan plan, boolean dryRun) {
        JsonArray artifacts = JsonUtils.array();
        boolean uploaded = !dryRun && plan.target() != null;
        for (PlannedArtifact artifact : plan.artifacts()) {
            JsonObject entry = JsonUtils.object();
            entry.put("kind", artifact.kind());
            entry.put("path", artifact.path().toAbsolutePath().normalize().toString());
            entry.put("bytes", artifact.bytes());
            if (artifact.sha256() != null) {
                entry.put("sha256", artifact.sha256());
            }
            if (artifact.url() != null) {
                entry.put("url", artifact.url());
            }
            entry.put("wouldWrite", dryRun && artifact.wouldWrite());
            entry.put("wouldUpload", artifact.wouldUpload());
            entry.put("uploaded", uploaded);
            artifacts.add(entry);
        }
        JsonObject project = JsonUtils.object();
        project.put("name", plan.project().name());
        project.put("version", plan.project().version());
        JsonResult result = JsonResult.start("publish")
                .workspace(plan.projectDir(), "single")
                .put("dryRun", dryRun)
                .put("target", plan.target())
                .put("project", project)
                .put("artifacts", artifacts)
                .put("uploaded", uploaded)
                .put("verified", uploaded && plan.target() != null
                        && !plan.target().startsWith("gh:"));
        if (plan.insecureHttp()) {
            result.diagnostic("warning", "PLAIN_HTTP_INSECURE",
                    "publishing over plain HTTP (" + plan.target() + ") — the traffic and"
                            + " JENO_PUBLISH_TOKEN are not encrypted", List.of());
        }
        result.emit(0);
    }

    // ------------------------------------------------------------------
    // native upload
    // ------------------------------------------------------------------

    private int upload(String target, String token, JenoProject project,
                       Path dist, String jarName, String descriptorName, String shaName) {
        String origin = target;
        String host = ArtifactLocations.githubHost();
        String owner = origin.substring(origin.indexOf(':') + 1, origin.indexOf('/'));
        String repo = origin.substring(origin.indexOf('/') + 1);
        String version = project.version();
        String tag = "v" + version;

        List<Path> files = List.of(
                dist.resolve(project.name() + "-" + version + ".jar"),
                dist.resolve(project.name() + "-" + version + ".jeno.toml"),
                dist.resolve(project.name() + "-" + version + ".sha256"));

        String releaseId;
        try {
            releaseId = GitHubReleases.create(origin, owner, repo, tag, token,
                    "{\"tag_name\":\"" + tag + "\",\"name\":\"" + tag
                            + "\",\"body\":\"released with jeno publish\"}");
        } catch (IOException | InterruptedException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }

        for (Path file : files) {
            try {
                GitHubReleases.upload(owner, repo, releaseId, file, token);
            } catch (IOException | InterruptedException e) {
                System.err.println(Ui.errPrefix() + "upload failed for " + file.getFileName() + ": "
                        + e.getMessage());
                return 1;
            }
        }

        System.out.println(Ui.ok("uploaded; share it with:"));
        System.out.println("  jeno add \"" + project.name() + "\" " + target + "@" + tag);

        System.out.println("  " + host + "/" + owner + "/" + repo + "/releases/tag/" + tag);
        return 0;
    }

    private static String descriptor(JenoProject project, java.util.Set<String> siblings) {
        StringBuilder out = new StringBuilder();
        out.append("[project]\n");
        out.append("name = \"").append(project.name()).append("\"\n");
        if (project.group() != null) {
            out.append("group = \"").append(escape(project.group())).append("\"\n");
        }
        out.append("version = \"").append(project.version()).append("\"\n");
        // identity facts travel with the release: they are what `jeno info`
        // shows a stranger who is deciding whether to depend on this
        if (project.summary() != null) {
            out.append("summary = \"").append(escape(project.summary())).append("\"\n");
        }
        if (project.homepage() != null) {
            out.append("homepage = \"").append(escape(project.homepage())).append("\"\n");
        }
        if (project.license() != null) {
            out.append("license = \"").append(escape(project.license())).append("\"\n");
        }
        if (project.javaVersion() != null) {
            out.append("\n[java]\nversion = ").append(project.javaVersion()).append('\n');
        }
        if (!project.requirements().isEmpty()) {
            out.append("\n[deps]\n");
            for (Requirement requirement : project.requirements()) {
                out.append("\"").append(requirement.id()).append("\" = \"")
                        .append(requirementValue(project, requirement,
                                siblings.contains(requirement.id())))
                        .append("\"\n");
            }
        }
        if (!project.apiRequirements().isEmpty()) {
            out.append("\n[deps-api]\n");
            for (Requirement requirement : project.requirements()) {
                if (!project.apiRequirements().contains(requirement.id())) {
                    continue;
                }
                out.append("\"").append(requirement.id()).append("\" = \"")
                        .append(requirementValue(project, requirement,
                                siblings.contains(requirement.id())))
                        .append("\"\n");
            }
        }
        return out.toString();
    }

    /** TOML basic-string escaping for the identity facts (quotes, backslashes). */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requirementValue(JenoProject project, Requirement requirement,
                                           boolean sibling) {
        return switch (requirement.species()) {
            case CENTRAL -> requirement.version();
            case GH -> requirement.origin() + "@" + requirement.tag();
            case URL -> requirement.url();
            case NAMED -> sibling
                    ? requirement.version()
                    : nativeDescriptorValue(namedSource(project, requirement),
                            requirement.version());
        };
    }

    /** Resolve a native requirement's canonical source for descriptor generation. */
    private static String namedSource(JenoProject project, Requirement requirement) {
        if (requirement.repositoryUrl() != null) {
            return requirement.repositoryUrl();
        }
        if (requirement.repository() != null) {
            Repository repository = project.repos().get(requirement.repository());
            if (repository != null) {
                return repository.declaration();
            }
        }
        throw new IllegalStateException("native requirement '" + requirement.id()
                + "' has no repository source");
    }

    /**
     * Descriptor value for an absolute native dependency. The repository alias
     * (if any) is deliberately not serialized; the canonical URL is the fact.
     */
    /** The structured [repos] hint for a jeno-native repository. */
    private static void printConsumeHint(Repository repository, JenoProject project) {
        String location = repository.location();
        System.out.println("  [repos.mine]");
        System.out.println("  url = \"" + escape(location) + "\"");
        System.out.println("  \"" + project.name() + "\" = \"mine:" + project.version() + "\"");
    }

    private static String nativeDescriptorValue(String source, String version) {
        Repository repository = Repository.parse(source);
        if (repository.layout() != Repository.Layout.JENO
                || repository.transport() != Repository.Transport.NETWORK) {
            throw new IllegalStateException(
                    "only network jeno sources can be serialized: " + source);
        }
        return repository.networkBase() + "@" + version;
    }


}
