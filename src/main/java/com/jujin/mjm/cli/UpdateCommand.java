package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.store.ArtifactStore;

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
 * {@code mjm update}: checks GitHub Releases for a newer version and
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
        return "update";
    }

    @Override
    public String[] aliases() {
        return new String[]{"upgrade"};
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
    public int execute(List<String> args) {
        if (!args.isEmpty()) {
            System.err.println("mjm: update takes no arguments — got '" + args.getFirst() + "'");
            return 2;
        }
        String latest;
        String tarGzUrl = null;
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(releasesApi())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("mjm: cannot check for updates (GitHub answered "
                        + response.statusCode() + ")");
                return 1;
            }
            var map = JsonUtils.parseObject(response.body()).toMap();
            latest = String.valueOf(map.get("tag_name"));
            tarGzUrl = findTarGzUrl(map);
        } catch (IOException | InterruptedException e) {
            System.err.println("mjm: cannot reach GitHub — check your network connection");
            return 1;
        }

        if (latest == null || latest.isBlank()) {
            System.err.println("mjm: GitHub returned no release tag");
            return 1;
        }
        String latestVersion = latest.startsWith("v") ? latest.substring(1) : latest;

        if (latestVersion.equals(Mjm.VERSION)) {
            System.out.println("mjm " + Mjm.VERSION + " — already at the latest version");
            return 0;
        }

        System.out.println("upgrading " + Mjm.VERSION + " → " + latestVersion + " ...");
        if (tarGzUrl == null || tarGzUrl.isBlank()) {
            System.err.println("mjm: no tar.gz asset found in release " + latest);
            return 1;
        }

        Path tempTar = null;
        Path tempDir = null;
        try {
            Fetch fetch = downloadWithRetry(tarGzUrl);
            if (fetch.body() == null) {
                System.err.println("mjm: download failed: " + fetch.failure());
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
                System.err.println("mjm: extraction failed");
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
                System.err.println("mjm: installation failed (exit " + exit + ")");
                return 1;
            }
            System.out.println("upgraded to mjm " + latestVersion
                    + " — takes effect on the next mjm invocation");
            return 0;
        } catch (IOException | InterruptedException e) {
            System.err.println("mjm: upgrade failed: " + e.getMessage());
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
