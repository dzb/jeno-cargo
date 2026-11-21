package com.jujin.mj.cli;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.store.ArtifactStore;

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
 * {@code mj update}: checks GitHub Releases for a newer version and
 * self-upgrades — download the tar.gz, extract, run the setup.sh inside.
 * The same install mechanism as the one-liner, so the upgrade is atomic
 * (fresh install to $HOME/.mj/, launcher regenerated, old version replaced).
 *
 * <p>{@code mj upgrade} is an alias — both verbs dispatch here.
 *
 * <p>The running process cannot replace its own jar — the upgrade takes
 * effect on the next {@code mj} invocation. This is standard behavior
 * (same as rustup, gh, npm -g).
 */
public final class UpdateCommand implements Command {

    /** The GitHub repo that hosts mj's releases. */
    private static final String RELEASES_API =
            "https://api.github.com/repos/dzb/mj-cargo/releases/latest";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Inject
    public UpdateCommand() {
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
    public String summary() {
        return "check for a newer version and self-upgrade from GitHub Releases";
    }

    @Override
    public int execute(List<String> args) {
        String latest;
        String tarGzUrl = null;
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(RELEASES_API)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("mj: cannot check for updates (GitHub answered "
                        + response.statusCode() + ")");
                return 1;
            }
            var map = JsonUtils.parseObject(response.body()).toMap();
            latest = String.valueOf(map.get("tag_name"));
            tarGzUrl = findTarGzUrl(map);
        } catch (IOException | InterruptedException e) {
            System.err.println("mj: cannot reach GitHub — check your network connection");
            return 1;
        }

        if (latest == null || latest.isBlank()) {
            System.err.println("mj: GitHub returned no release tag");
            return 1;
        }
        String latestVersion = latest.startsWith("v") ? latest.substring(1) : latest;

        if (latestVersion.equals(Mj.VERSION)) {
            System.out.println("mj " + Mj.VERSION + " — already at the latest version");
            return 0;
        }

        System.out.println("upgrading " + Mj.VERSION + " → " + latestVersion + " ...");
        if (tarGzUrl == null || tarGzUrl.isBlank()) {
            System.err.println("mj: no tar.gz asset found in release " + latest);
            return 1;
        }

        try {
            // download to temp
            Path tempTar = Files.createTempFile("mj-upgrade-", ".tar.gz");
            HttpResponse<byte[]> download = http.send(
                    HttpRequest.newBuilder(URI.create(tarGzUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (download.statusCode() != 200) {
                System.err.println("mj: download failed (" + download.statusCode() + ")");
                return 1;
            }
            Files.write(tempTar, download.body());

            // extract to temp
            Path tempDir = Files.createTempDirectory("mj-upgrade-");
            Process tar = new ProcessBuilder("tar", "-xzf", tempTar.toString(),
                    "-C", tempDir.toString()).inheritIO().start();
            if (tar.waitFor() != 0) {
                System.err.println("mj: extraction failed");
                return 1;
            }

            // find and run the setup.sh inside the distribution
            Path installScript = tempDir.resolve("mj-" + latestVersion + "/setup.sh");
            try (Stream<Path> stream = Files.walk(tempDir, 2)) {
                installScript = stream
                        .filter(path -> path.getFileName().toString().equals("setup.sh"))
                        .findFirst()
                        .orElse(installScript);
            }
            Process install = new ProcessBuilder("bash", installScript.toString())
                    .inheritIO().start();
            int exit = install.waitFor();

            // cleanup
            Files.deleteIfExists(tempTar);
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                // best-effort cleanup
                            }
                        });
            }

            if (exit != 0) {
                System.err.println("mj: installation failed (exit " + exit + ")");
                return 1;
            }
            System.out.println("upgraded to mj " + latestVersion
                    + " — takes effect on the next mj invocation");
            return 0;
        } catch (IOException | InterruptedException e) {
            System.err.println("mj: upgrade failed: " + e.getMessage());
            return 1;
        }
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
