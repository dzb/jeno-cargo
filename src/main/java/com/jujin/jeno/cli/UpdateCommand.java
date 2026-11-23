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
 * {@code mjm upgrade} ({@code mjm update} is an alias): checks GitHub Releases for a newer version and
 * self-upgrades — download the tar.gz, extract, run the setup.sh inside.
 * The same install mechanism as the one-liner, so the upgrade is atomic
 * (fresh install to $HOME/.mjm/, launcher regenerated, old version replaced).
 *
 * <p>{@code mjm upgrade} is an alias — both verbs dispatch here.
 *
 * <p>The running process cannot replace its own jar — the upgrade takes
 * effect on the next {@code mjm} invocation. This is standard behavior
 * (same as rustup, gh, npm -g).
 */
public final class UpdateCommand implements Command {

    /** The default GitHub repo that hosts mjm's releases. */
    private static final String RELEASES_API_DEFAULT =
            "https://api.github.com/repos/dzb/mjm-cargo/releases/latest";

    /**
     * GitHub's release assets have taken tens of seconds to appear at the CDN
     * edge (two releases measured 20–60s). Five tries with 500ms, 2s, 4s and 8s
     * between them give the asset about fifteen seconds to show up, and still
     * fail in bounded time.
     *
     * <p>The waits are injectable ({@code -Dmjm.update.backoff.ms=1,1,1,1}) so
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
     * update test ({@code -Dmjm.update.api=<full releases/latest URL>}).
     */
    private static String releasesApi() {
        return System.getProperty("mjm.update.api", RELEASES_API_DEFAULT);
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
        String tarGzUrl = null;
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(releasesApi())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return fail(json, 1, "UPDATE_CHECK_FAILED",
                        "cannot check for updates (GitHub answered " + response.statusCode() + ")");
            }
            var map = JsonUtils.parseObject(response.body()).toMap();
            latest = String.valueOf(map.get("tag_name"));
            tarGzUrl = findTarGzUrl(map);
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
                System.out.println("mjm " + Jeno.VERSION + " — already at the latest version");
            } else if (updateAvailable) {
                System.out.println("mjm " + Jeno.VERSION + " → " + latestVersion
                        + " — update available");
            } else {
                System.out.println("mjm " + Jeno.VERSION + " — latest release is " + latestVersion);
            }
            return 0;
        }

        if (!updateAvailable) {
            if (json) {
                emitResult(json, latestVersion, false, false, false, false);
            } else {
                System.out.println("mjm " + Jeno.VERSION + " — already at the latest version");
            }
            return 0;
        }

        if (tarGzUrl == null || tarGzUrl.isBlank()) {
            return fail(json, 1, "RELEASE_ASSET_MISSING",
                    "no tar.gz asset found in release " + latest);
        }

        if (json) {
            String versionToInstall = latestVersion;
            String urlToInstall = tarGzUrl;
            JsonCapture.Result captured = JsonCapture.run(() ->
                    install(versionToInstall, urlToInstall));
            if (captured.exitCode() != 0) {
                JsonResult.error("upgrade", captured.exitCode(), "UPGRADE_FAILED", captured.message())
                        .emit(captured.exitCode());
                return captured.exitCode();
            }
            emitResult(json, latestVersion, true, false, true, true);
            return 0;
        }

        System.out.println("upgrading " + Jeno.VERSION + " → " + latestVersion + " ...");
        return install(latestVersion, tarGzUrl);
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

    private int install(String latestVersion, String tarGzUrl) {
        Path tempTar = null;
        Path tempDir = null;
        try {
            Fetch fetch = downloadWithRetry(tarGzUrl);
            if (fetch.body() == null) {
                System.err.println(Ui.errPrefix() + "download failed: " + fetch.failure());
                if (fetch.propagationLikely()) {
                    System.err.println("  the release asset may not have propagated yet — "
                            + "retry in a moment; if it keeps failing, download it from "
                            + tarGzUrl);
                }
                return 1;
            }

            // download to temp
            tempTar = Files.createTempFile("mjm-upgrade-", ".tar.gz");
            Files.write(tempTar, fetch.body());

            // extract to temp
            tempDir = Files.createTempDirectory("mjm-upgrade-");
            Process tar = new ProcessBuilder("tar", "-xzf", tempTar.toString(),
                    "-C", tempDir.toString()).inheritIO().start();
            if (tar.waitFor() != 0) {
                System.err.println(Ui.errPrefix() + "extraction failed");
                return 1;
            }

            // find and run the setup.sh inside the distribution
            Path installScript = tempDir.resolve("mjm-" + latestVersion + "/setup.sh");
            try (Stream<Path> stream = Files.walk(tempDir, 2)) {
                installScript = stream
                        .filter(path -> path.getFileName().toString().equals("setup.sh"))
                        .findFirst()
                        .orElse(installScript);
            }
            Process install = installProcess(installScript);
            int exit = install.waitFor();

            if (exit != 0) {
                System.err.println(Ui.errPrefix() + "installation failed (exit " + exit + ")");
                return 1;
            }
            System.out.println("upgraded to mjm " + latestVersion
                    + " — takes effect on the next mjm invocation");
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
     * The tarball download, retried. A just-created release can 404 or be cut
     * off at the CDN edge for a few seconds; both are transient, so an
     * IOException (EOF, reset, timeout) and 5xx/429 are retried three times with
     * a short back-off. A 4xx (other than 429) is a client-side fact and is not
     * retried. The releases API lookup is deliberately still one-shot.
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
                boolean transientStatus = status >= 500 || status == 429;
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
        String override = System.getProperty("mjm.update.backoff.ms");
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
     * The installer subprocess. {@code -Dmjm.update.home=<dir>} redirects the
     * packaged setup.sh's {@code $HOME} — the hermetic-update-test seam
     * (and an off-home install root, should one ever be wanted).
     */
    private static Process installProcess(Path installScript) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("bash", installScript.toString());
        String home = System.getProperty("mjm.update.home");
        if (home != null && !home.isBlank()) {
            builder.environment().put("HOME", home);
        }
        return builder.inheritIO().start();
    }

    private String findTarGzUrl(Map<String, Object> release) {
        if (release.get("assets") instanceof List<?> assets) {
            for (Object asset : assets) {
                if (asset instanceof Map<?, ?> assetMap) {
                    String name = String.valueOf(assetMap.get("name"));
                    if (name.endsWith(".tar.gz")) {
                        return String.valueOf(assetMap.get("browser_download_url"));
                    }
                }
            }
        }
        return null;
    }

}
