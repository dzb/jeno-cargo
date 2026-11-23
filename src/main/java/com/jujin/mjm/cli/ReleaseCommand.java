package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.project.Repository;
import com.jujin.mjm.resolve.ArtifactLocations;
import com.jujin.mjm.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code mjm release [target] [--dry-run]} — one step: take the installer that
 * {@code mjm bundle} produced and publish it.
 *
 * <p>It never builds, tests or bundles: those decisions belong to a project's
 * CI/flow, and a release step that quietly rebuilds is a release step whose
 * bytes nobody reviewed. The only input is
 * {@code target/dist/<name>-<version>.tar.gz}.
 *
 * <p>Without a target it prepares {@code target/dist/release/} and prints the
 * upload checklist. With {@code gh:}, {@code mjm:https://…} or
 * {@code mjm:local:<dir>} it uploads the installer, a bootstrap {@code setup.sh},
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
        return "[target] [--dry-run] [--allow-unsigned]";
    }

    @Override
    public String summary() {
        return "publish a bundled installer: gh:, mjm:https://host/, mjm:local:<dir>";
    }

    @Override
    public String help() {
        return """
               needs target/dist/<name>-<version>.tar.gz (run mjm bundle first);
               signed with minisign by default: set MJM_MINISIGN_KEY, and optionally
               MJM_MINISIGN_PUBKEY/MJM_MINISIGN; --allow-unsigned is the explicit opt-out.
               no target prepares target/dist/release/ and prints the upload list.
               --dry-run prepares the same files and shows the URLs without uploading.""";
    }

    @Override
    public int execute(List<String> args) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new ReleaseCommand(dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean dryRun = false;
        boolean allowUnsigned = false;
        String target = null;
        for (String arg : args) {
            if (arg.equals("--dry-run")) {
                dryRun = true;
            } else if (arg.equals("--allow-unsigned")) {
                allowUnsigned = true;
            } else if (arg.startsWith("-")) {
                System.err.println("mjm: release takes [target], --dry-run and"
                        + " --allow-unsigned — got '" + arg + "'");
                return 2;
            } else if (target == null) {
                target = arg;
            } else {
                System.err.println("mjm: release takes at most one target — got '" + arg + "'");
                return 2;
            }
        }

        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
        } catch (IOException e) {
            System.err.println("mjm: no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        }

        String name = project.name();
        String version = project.version();
        String tag = "v" + version;
        Path tar = projectDir.resolve(DIST).resolve(name + "-" + version + ".tar.gz");
        if (!Files.isRegularFile(tar)) {
            System.err.println("mjm: " + tar + " is missing — run mjm bundle first");
            return 1;
        }

        int jdk = project.javaVersion() != null
                ? project.javaVersion() : Runtime.version().feature();
        String sha;
        try {
            sha = ArtifactStore.sha256(tar);
        } catch (RuntimeException e) {
            System.err.println("mjm: cannot read " + tar + " — " + e.getMessage());
            return 1;
        }

        ReleaseSigner.Preview signing;
        try {
            signing = ReleaseSigner.prepare(allowUnsigned);
        } catch (ReleaseSigner.SigningException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }

        Plan plan;
        try {
            plan = plan(target, name, version, tag, project);
        } catch (IllegalArgumentException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }

        Path releaseDir = projectDir.resolve(RELEASE);
        List<Path> files;
        try {
            Files.createDirectories(releaseDir);
            String tarName = tar.getFileName().toString();
            Path shaFile = releaseDir.resolve(tarName + ".sha256");
            Path setup = releaseDir.resolve("setup.sh");
            Path json = releaseDir.resolve("release.json");
            List<String> signatureNames = signing.signed()
                    ? List.of("setup.sh.minisig", "release.json.minisig", tarName + ".minisig")
                    : List.of();
            Files.writeString(shaFile, sha + "\n");
            Files.writeString(setup, bootstrap(name, version, plan.installerUrl, sha,
                    signing));
            Files.writeString(json, releaseJson(name, version, tarName, sha, jdk,
                    signing, signatureNames));

            List<Path> signatures;
            try {
                signatures = ReleaseSigner.sign(releaseDir, List.of(setup, json, tar), signing);
            } catch (ReleaseSigner.SigningException e) {
                System.err.println("mjm: " + e.getMessage());
                return 1;
            }
            List<Path> all = new ArrayList<>(List.of(setup, shaFile, json));
            all.addAll(signatures);
            if (signing.signed()) {
                Path pub = releaseDir.resolve("minisign.pub");
                Files.writeString(pub, "untrusted comment: minisign public key\n"
                        + signing.publicKey() + "\n");
                all.add(pub);
            }
            files = List.copyOf(all);
        } catch (IOException e) {
            System.err.println("mjm: cannot prepare " + releaseDir + " — " + e.getMessage());
            return 1;
        }

        System.out.println("release files in " + releaseDir + ":");
        System.out.println("  " + tar);
        for (Path file : files) {
            System.out.println("  " + file);
        }

        if (plan.target == null) {
            System.out.println("upload these four files together, then share the setup.sh;");
            System.out.println("  bootstrap installer URL: " + plan.installerUrl);
            return 0;
        }
        if (dryRun) {
            System.out.println("dry run — would upload:");
            System.out.println("  " + plan.installerUrl);
            for (String url : plan.auxiliaryUrls) {
                System.out.println("  " + url);
            }
            return 0;
        }

        return plan.repository == null
                ? publishToGitHub(plan, tar, files, name, version, tag, sha, signing)
                : publishToRepository(plan, tar, files, sha, signing);
    }

    // ------------------------------------------------------------------
    // targets
    // ------------------------------------------------------------------

    private record Plan(Repository repository, String target, String name, String version,
                        String installerUrl, String bootstrapUrl, List<String> auxiliaryUrls) {}

    private Plan plan(String target, String name, String version, String tag, MjProject project) {
        if (target == null) {
            Path tar = projectDir.resolve(DIST).resolve(name + "-" + version + ".tar.gz");
            return new Plan(null, null, name, version, tar.toUri().toString(), null, List.of());
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
                    + "/releases/latest/download/setup.sh";
            String tar = base + "/" + name + "-" + version + ".tar.gz";
            List<String> urls = List.of(base + "/setup.sh", tar + ".sha256",
                    base + "/release.json");
            return new Plan(null, target, name, version, tar, stable, urls);
        }

        Repository repository = Repository.parse(target);
        if (repository.layout() != Repository.Layout.MJM) {
            throw new IllegalArgumentException("release publishes installers, not Maven "
                    + "artifacts — use gh:<owner>/<repo>, mjm:https://host/ or "
                    + "mjm:local:<dir>");
        }
        String root = repository.isLocal()
                ? repository.directory(ArtifactLocations.projectRoot()).toUri().toString()
                : repository.networkBase();
        if (!root.endsWith("/")) {
            root = root + "/";
        }
        String versioned = root + name + "/" + version + "/";
        String stable = root + name + "/setup.sh";
        String tar = versioned + "release/" + name + "-" + version + ".tar.gz";
        List<String> urls = List.of(versioned + "release/setup.sh", tar + ".sha256",
                versioned + "release/release.json", versioned + "setup.sh", stable,
                versioned + "minisign.pub", root + name + "/minisign.pub");
        return new Plan(repository, target, name, version, tar, stable, urls);
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
                writeAtomically(localDirectory(plan).resolve("setup.sh"),
                        Files.readAllBytes(files.getFirst()));
                Path stable = localDirectory(plan).getParent().resolve("setup.sh");
                writeAtomically(stable, Files.readAllBytes(files.getFirst()));
                if (signing.signed()) {
                    Path pub = files.stream().filter(path -> path.getFileName().toString()
                            .equals("minisign.pub")).findFirst().orElseThrow();
                    writeAtomically(localDirectory(plan).resolve("minisign.pub"),
                            Files.readAllBytes(pub));
                    writeAtomically(localDirectory(plan).getParent().resolve("minisign.pub"),
                            Files.readAllBytes(pub));
                }
            } else {
                MjmUploads.put(plan.installerUrl, Files.readAllBytes(tar));
                System.out.println("  PUT " + plan.installerUrl);
                for (Path file : files) {
                    String url = releaseUrl(plan, file.getFileName().toString());
                    MjmUploads.put(url, Files.readAllBytes(file));
                    System.out.println("  PUT " + url);
                }
                MjmUploads.put(plan.auxiliaryUrls.get(3), Files.readAllBytes(files.getFirst()));
                MjmUploads.put(plan.auxiliaryUrls.get(4), Files.readAllBytes(files.getFirst()));
                if (signing.signed()) {
                    Path pub = files.stream().filter(path -> path.getFileName().toString()
                            .equals("minisign.pub")).findFirst().orElseThrow();
                    byte[] bytes = Files.readAllBytes(pub);
                    MjmUploads.put(plan.auxiliaryUrls.get(5), bytes);
                    MjmUploads.put(plan.auxiliaryUrls.get(6), bytes);
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("mjm: release upload failed — " + e.getMessage());
            return 1;
        }

        try {
            byte[] readBack = plan.repository.isLocal()
                    ? Files.readAllBytes(localReleaseTar(plan))
                    : MjmUploads.get(plan.installerUrl);
            String actual = ArtifactStore.sha256(readBack);
            if (!actual.equals(sha)) {
                System.err.println("mjm: release verification failed for " + plan.installerUrl
                        + " — wrote " + sha + ", read " + actual);
                return 1;
            }
            if (signing.signed()) {
                Path signature = projectDir.resolve(RELEASE)
                        .resolve(tar.getFileName() + ".minisig");
                ReleaseSigner.verify(tar, signature, signing.publicKey());
                if (!plan.repository.isLocal()) {
                    byte[] remoteSignature = MjmUploads.get(plan.installerUrl + ".minisig");
                    Path temp = Files.createTempFile("mjm-remote-", ".minisig");
                    try {
                        Files.write(temp, remoteSignature);
                        ReleaseSigner.verify(tar, temp, signing.publicKey());
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                }
            }
            if (!plan.repository.isLocal()) {
                MjmUploads.get(plan.auxiliaryUrls.get(0));
                MjmUploads.get(plan.auxiliaryUrls.get(2));
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("mjm: release verification failed — " + e.getMessage());
            return 1;
        }

        System.out.println("released " + plan.target + "; installer:");
        System.out.println("  curl -fsSL " + plan.bootstrapUrl + " | bash");
        return 0;
    }

    private int publishToGitHub(Plan plan, Path tar, List<Path> files,
                                String name, String version, String tag, String sha,
                                ReleaseSigner.Preview signing) {
        String token = GitHubReleases.token();
        if (token == null || token.isBlank()) {
            System.err.println("mjm: MJM_GITHUB_TOKEN environment variable is not set — "
                    + "set it to your GitHub personal access token, or run with --dry-run");
            return 1;
        }
        String origin = "gh:" + plan.target.substring("gh:".length());
        String owner = origin.substring("gh:".length(), origin.indexOf('/'));
        String repo = origin.substring(origin.indexOf('/') + 1);

        String releaseId;
        String tarDownload;
        try {
            releaseId = GitHubReleases.create(origin, owner, repo, tag, token,
                    "{\"tag_name\":\"" + tag + "\",\"name\":\"" + tag
                            + "\",\"body\":\"released with mjm release\"}");
            tarDownload = null;
            for (Path file : concat(tar, files)) {
                String download = GitHubReleases.upload(owner, repo, releaseId, file, token);
                if (file.equals(tar)) {
                    tarDownload = download;
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("mjm: " + e.getMessage());
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
                System.err.println("mjm: release verification failed for " + tarDownload
                        + " — uploaded " + sha + ", read " + actual);
                return 1;
            }
            if (signing.signed()) {
                Path signature = projectDir.resolve(RELEASE)
                        .resolve(tar.getFileName() + ".minisig");
                Path temp = Files.createTempFile("mjm-gh-", ".tar.gz");
                try {
                    Files.write(temp, readBack);
                    ReleaseSigner.verify(temp, signature, signing.publicKey());
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("mjm: release verification failed for " + tarDownload
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
        Path temp = Files.createTempFile(target.getParent(), ".mjm-release-", ".tmp");
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
            "$TMP/%1$s-%2$s/setup.sh"
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
        root.put("mjm", Mjm.VERSION);
        root.put("platform", "any");
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
