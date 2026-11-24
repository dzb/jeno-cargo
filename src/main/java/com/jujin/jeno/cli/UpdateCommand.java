package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.jeno.store.ArtifactStore;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code jeno upgrade} ({@code jeno update} is an alias): checks GitHub Releases for a newer version and
 * self-upgrades — download the tar.gz, extract, run the install.sh inside.
 * The same install mechanism as the one-liner, so the upgrade is atomic
 * (fresh install to $HOME/.jeno/, launcher regenerated, old version replaced).
 *
 * <p>{@code jeno upgrade} is an alias — both verbs dispatch here.
 *
 * <p>The running process cannot replace its own jar — the upgrade takes
 * effect on the next {@code jeno} invocation. This is standard behavior
 * (same as rustup, gh, npm -g).
 */
public final class UpdateCommand implements Command {

    /** The default GitHub repo that hosts jeno's releases. */
    private static final String RELEASES_API_DEFAULT =
            "https://api.github.com/repos/dzb/jeno-cargo/releases/latest";

    /**
     * GitHub's release assets have taken tens of seconds to appear at the CDN
     * edge (two releases measured 20–60s). Five tries with 500ms, 2s, 4s and 8s
     * between them give the asset about fifteen seconds to show up, and still
     * fail in bounded time.
     *
     * <p>The waits are injectable ({@code -Djeno.update.backoff.ms=1,1,1,1}) so
     * the retry tests do not spend those fifteen seconds proving the logic.
     */
    private static final int DOWNLOAD_ATTEMPTS = 5;
    private static final long[] BACKOFF_MS = {500L, 2_000L, 4_000L, 8_000L};

    // HTTP/1.1 on purpose: the update check reads one JSON document; HTTP/2's
    // multiplexing buys nothing and it failed on this machine where HTTP/1.1
    // succeeded on every attempt (the same GitHub release path the store hit).
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Inject
    public UpdateCommand() {
    }

    /**
     * The releases URL — overridable for in-house mirrors and the hermetic
     * update test ({@code -Djeno.update.api=<full releases/latest URL>}).
     */
    private static String releasesApi() {
        return System.getProperty("jeno.update.api", RELEASES_API_DEFAULT);
    }

    @Override
    public String name() {
        return "upgrade";
    }

    @Override
    public String[] aliases() {
        return new String[]{"update"};
    }

    @Override
    public boolean supportsOffline() {
        return false;
    }

    @Override
    public String summary() {
        return "check for a newer release and self-upgrade";
    }

    @Override
    public Command.Category category() {
        return Command.Category.MAINTAIN;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.ALWAYS)
                .writes(CommandCapabilities.WriteScope.USER_INSTALL)
                .workspace(CommandCapabilities.WorkspaceMode.GLOBAL)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        boolean checkOnly = false;
        boolean json = false;
        for (String arg : args) {
            switch (arg) {
                case "--check" -> checkOnly = true;
                case "--json" -> json = true;
                default -> {
                    String message = "upgrade takes --check only — got '" + arg + "'";
                    if (json) {
                        JsonResult.error("upgrade", 2, "USAGE", message).emit(2);
                    } else {
                        System.err.println(Ui.errPrefix() + message);
                    }
                    return 2;
                }
            }
        }

        String latest;
        ReleaseAsset asset;
        Map<String, Object> releaseMap = null;
        boolean legacyLayout = false;
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(releasesApi())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return fail(json, 1, "UPDATE_CHECK_FAILED",
                        "cannot check for updates (GitHub answered " + response.statusCode() + ")");
            }
            var map = JsonUtils.parseObject(response.body()).toMap();
            releaseMap = map;
            latest = String.valueOf(map.get("tag_name"));
            String latestVersion = latest == null ? null
                    : (latest.startsWith("v") ? latest.substring(1) : latest);
            asset = findReleaseAsset(map, latestVersion);
            legacyLayout = hasLegacyAsset(map);
        } catch (IOException | InterruptedException e) {
            return fail(json, 1, "NETWORK_FAILED",
                    "cannot reach GitHub — check your network connection");
        }

        if (latest == null || latest.isBlank() || "null".equals(latest)) {
            return fail(json, 1, "RELEASE_INVALID", "GitHub returned no release tag");
        }
        String latestVersion = latest.startsWith("v") ? latest.substring(1) : latest;
        boolean updateAvailable = !latestVersion.equals(Jeno.VERSION);

        if (checkOnly) {
            if (json) {
                emitResult(json, latestVersion, updateAvailable, true, false, false);
            } else if (latestVersion.equals(Jeno.VERSION)) {
                System.out.println("jeno " + Jeno.VERSION + " — already at the latest version");
            } else if (updateAvailable) {
                System.out.println("jeno " + Jeno.VERSION + " → " + latestVersion
                        + " — update available");
            } else {
                System.out.println("jeno " + Jeno.VERSION + " — latest release is " + latestVersion);
            }
            return 0;
        }

        if (!updateAvailable) {
            if (json) {
                emitResult(json, latestVersion, false, false, false, false);
            } else {
                System.out.println("jeno " + Jeno.VERSION + " — already at the latest version");
            }
            return 0;
        }

        if (asset == null) {
            String message = legacyLayout
                    ? "release " + latest + " uses the old jeno-bin-* asset name — "
                        + "this release predates the jeno layout; install it manually once, "
                        + "then upgrade again"
                    : "release " + latest + " is missing "
                        + String.join(", ", missingAssets(releaseMap, latestVersion))
                        + " — install it manually once, then upgrade again";
            return fail(json, 1, "RELEASE_ASSET_MISSING", message);
        }

        if (json) {
            String versionToInstall = latestVersion;
            ReleaseAsset assetToInstall = asset;
            JsonCapture.Result captured = JsonCapture.run(() ->
                    install(versionToInstall, assetToInstall));
            if (captured.exitCode() != 0) {
                JsonResult.error("upgrade", captured.exitCode(), "UPGRADE_FAILED", captured.message())
                        .emit(captured.exitCode());
                return captured.exitCode();
            }
            emitResult(json, latestVersion, true, false, true, true);
            return 0;
        }

        System.out.println("upgrading " + Jeno.VERSION + " → " + latestVersion + " ...");
        return install(latestVersion, asset);
    }

    private void emitResult(boolean json, String latestVersion, boolean updateAvailable,
                            boolean checkOnly, boolean upgraded, boolean applied) {
        JsonResult.start("upgrade")
                .put("checkOnly", checkOnly)
                .put("currentVersion", Jeno.VERSION)
                .put("latestVersion", latestVersion)
                .put("updateAvailable", updateAvailable)
                .put("upgraded", upgraded)
                .put("takesEffectNextInvocation", upgraded)
                .emit(0);
    }

    private int fail(boolean json, int exit, String code, String message) {
        if (json) {
            JsonResult.error("upgrade", exit, code, message).emit(exit);
        } else {
            System.err.println(Ui.errPrefix() + message);
        }
        return exit;
    }

    private int install(String latestVersion, ReleaseAsset asset) {
        Path tempTar = null;
        Path tempDir = null;
        try {
            if (isWindows()) {
                Fetch installer = downloadWithRetry(asset.installerUrl());
                if (installer.body() == null) {
                    System.err.println(Ui.errPrefix() + "download of install.ps1 failed: "
                            + installer.failure());
                    return 1;
                }
                Path ps1 = Files.createTempFile("jeno-install-", ".ps1");
                Files.write(ps1, installer.body());
                Process install = installProcess(ps1);
                int exit = install.waitFor();
                Files.deleteIfExists(ps1);
                if (exit != 0) {
                    System.err.println(Ui.errPrefix() + "installation failed (exit " + exit + ")");
                    return 1;
                }
                System.out.println("upgraded to jeno " + latestVersion
                        + " — takes effect on the next jeno invocation");
                return 0;
            }

            Fetch fetch = downloadWithRetry(asset.tarUrl());
            if (fetch.body() == null) {
                System.err.println(Ui.errPrefix() + "download failed: " + fetch.failure());
                if (fetch.propagationLikely()) {
                    System.err.println("  the release asset may not have propagated yet — "
                            + "retry in a moment; if it keeps failing, download it from "
                            + asset.tarUrl());
                }
                return 1;
            }

            Fetch checksum = downloadWithRetry(asset.shaUrl());
            if (checksum.body() == null) {
                System.err.println(Ui.errPrefix() + "cannot read " + asset.shaUrl()
                        + " — " + checksum.failure());
                if (checksum.propagationLikely()) {
                    System.err.println("  the release asset may not have propagated yet — "
                            + "retry in a moment; if it keeps failing, download it from "
                            + asset.shaUrl());
                }
                return 1;
            }
            String expected = new String(checksum.body(), java.nio.charset.StandardCharsets.UTF_8)
                    .strip().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
            String actual = ArtifactStore.sha256(fetch.body());
            if (!actual.equals(expected)) {
                System.err.println(Ui.errPrefix() + "sha256 mismatch for " + asset.tarUrl()
                        + " — expected " + expected + ", got " + actual);
                return 1;
            }

            // download to temp
            tempTar = Files.createTempFile("jeno-upgrade-", ".tar.gz");
            Files.write(tempTar, fetch.body());

            // extract to temp
            tempDir = Files.createTempDirectory("jeno-upgrade-");
            Process tar = new ProcessBuilder("tar", "-xzf", tempTar.toString(),
                    "-C", tempDir.toString()).inheritIO().start();
            if (tar.waitFor() != 0) {
                System.err.println(Ui.errPrefix() + "extraction failed");
                return 1;
            }

            // The new package calls its POSIX installer install.sh. A package
            // with only install.sh predates the jeno layout and needs one manual
            // install (documented in RELEASE.md).
            Path installScript = tempDir.resolve("jeno-" + latestVersion + "/install.sh");
            try (Stream<Path> stream = Files.walk(tempDir, 2)) {
                installScript = stream
                        .filter(path -> path.getFileName().toString().equals("install.sh"))
                        .findFirst()
                        .orElse(installScript);
            }
            if (!Files.isRegularFile(installScript)) {
                System.err.println(Ui.errPrefix() + "release " + latestVersion
                        + " has no install.sh — it predates the jeno layout; "
                        + "install that release manually once, then upgrade again");
                return 1;
            }
            Process install = installProcess(installScript);
            int exit = install.waitFor();

            if (exit != 0) {
                System.err.println(Ui.errPrefix() + "installation failed (exit " + exit + ")");
                return 1;
            }
            System.out.println("upgraded to jeno " + latestVersion
                    + " — takes effect on the next jeno invocation");
            return 0;
        } catch (IOException | InterruptedException e) {
            System.err.println(Ui.errPrefix() + "upgrade failed: " + e.getMessage());
            return 1;
        } finally {
            // the temp tree goes away whether the upgrade succeeded, failed or
            // was interrupted; nothing half-downloaded outlives the command
            if (tempTar != null) {
                try {
                    Files.deleteIfExists(tempTar);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            }
            deleteTree(tempDir);
        }
    }

    /** The release asset, or the reason it could not be fetched. */
    private record Fetch(byte[] body, String failure, boolean propagationLikely) {}

    /**
     * The tarball download, retried. A just-created release asset can be
     * missing from the CDN (404) or cut off at the edge for a few seconds;
     * both are transient, so an IOException (EOF, reset, timeout), 404,
     * 5xx and 429 are retried with the short back-off. Authentication or
     * request errors (401/403/422) are client-side facts and are not retried.
     * The releases API lookup is deliberately still one-shot.
     */
    private Fetch downloadWithRetry(String url) throws InterruptedException {
        long[] backoff = backoffMillis();
        String lastFailure = "no attempt made";
        boolean propagationLikely = false;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = http.send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) {
                    return new Fetch(response.body(), null, false);
                }
                boolean transientStatus = status == 404 || status >= 500 || status == 429;
                lastFailure = "server answered " + status;
                if (!transientStatus) {
                    return new Fetch(null, lastFailure, false);
                }
                propagationLikely = true;
            } catch (IOException e) {
                lastFailure = e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage();
                propagationLikely = true;
            }
            if (attempt < DOWNLOAD_ATTEMPTS) {
                System.err.println("download attempt " + attempt + " failed ("
                        + lastFailure + ") — retrying");
                Thread.sleep(backoff[Math.min(attempt - 1, backoff.length - 1)]);
            }
        }
        return new Fetch(null, lastFailure, propagationLikely);
    }

    /** The shipped back-off, or the test override when one is set. */
    static long[] backoffMillis() {
        String override = System.getProperty("jeno.update.backoff.ms");
        if (override != null && !override.isBlank()) {
            return java.util.Arrays.stream(override.split(","))
                    .map(String::strip)
                    .filter(part -> !part.isEmpty())
                    .mapToLong(Long::parseLong)
                    .toArray();
        }
        return BACKOFF_MS;
    }

    private static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            });
        } catch (IOException e) {
            // best-effort cleanup
        }
    }

    /**
     * The installer subprocess. {@code -Djeno.update.home=<dir>} redirects the
     * packaged install.sh's {@code $HOME} — the hermetic-update-test seam
     * (and an off-home install root, should one ever be wanted).
     */
    private static Process installProcess(Path installScript) throws IOException {
        ProcessBuilder builder;
        if (isWindows()) {
            String powershell = System.getProperty("jeno.update.powershell", "powershell");
            builder = new ProcessBuilder(powershell, "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-File", installScript.toString());
        } else {
            builder = new ProcessBuilder("bash", installScript.toString());
            String home = System.getProperty("jeno.update.home");
            if (home != null && !home.isBlank()) {
                builder.environment().put("HOME", home);
            }
        }
        return builder.inheritIO().start();
    }

    private static boolean isWindows() {
        String override = System.getProperty("jeno.update.os");
        if (override != null) {
            return override.equalsIgnoreCase("windows");
        }
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private record ReleaseAsset(String tarUrl, String shaUrl, String installerUrl) {}

    private java.util.List<String> missingAssets(Map<String, Object> release, String version) {
        java.util.Set<String> present = new java.util.HashSet<>();
        if (release.get("assets") instanceof List<?> assets) {
            for (Object asset : assets) {
                if (asset instanceof Map<?, ?> assetMap) {
                    present.add(String.valueOf(assetMap.get("name")));
                }
            }
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String name : List.of("jeno-" + version + ".tar.gz",
                "jeno-" + version + ".tar.gz.sha256", "install.ps1")) {
            if (!present.contains(name)) {
                missing.add(name);
            }
        }
        return missing;
    }

    private boolean hasLegacyAsset(Map<String, Object> release) {
        if (release.get("assets") instanceof List<?> assets) {
            for (Object asset : assets) {
                if (asset instanceof Map<?, ?> assetMap
                        && String.valueOf(assetMap.get("name")).startsWith("jeno-bin-")) {
                    return true;
                }
            }
        }
        return false;
    }

    private ReleaseAsset findReleaseAsset(Map<String, Object> release, String version) {
        if (release.get("assets") instanceof List<?> assets) {
            String tarName = "jeno-" + version + ".tar.gz";
            String shaName = tarName + ".sha256";
            String ps1Name = "install.ps1";
            String tar = null;
            String sha = null;
            String ps1 = null;
            for (Object asset : assets) {
                if (asset instanceof Map<?, ?> assetMap) {
                    String name = String.valueOf(assetMap.get("name"));
                    String url = String.valueOf(assetMap.get("browser_download_url"));
                    if (name.equals(tarName)) {
                        tar = url;
                    } else if (name.equals(shaName)) {
                        sha = url;
                    } else if (name.equals(ps1Name)) {
                        ps1 = url;
                    }
                }
            }
            if (tar != null && sha != null && ps1 != null) {
                return new ReleaseAsset(tar, sha, ps1);
            }
        }
        return null;
    }

}
