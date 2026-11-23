package com.jujin.mjm.cli;

import com.jujin.mjm.project.Repository;
import com.jujin.mjm.resolve.ArtifactLocations;
import com.jujin.mjm.resolve.Versions;
import com.jujin.mjm.store.LockFileReader;
import com.jujin.mjm.store.LockedArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code mjm outdated} — which pinned dependencies have newer versions.
 *
 * <p>mjm pins exact versions, so "outdated" is not a decision it makes for
 * you: it reports what exists above what you pinned, and you choose. Nothing
 * is written; {@code mjm lock} is what applies a change.
 *
 * <p>Only Central artifacts can be checked. A {@code gh:} release carries no
 * version index — the tag is the version — and a bare URL leaf has no metadata
 * at all. Those are listed as skipped rather than silently omitted, because
 * "no update found" and "cannot know" are different answers.
 *
 * <p>The comparison uses the same ordering the resolver uses
 * ({@link Versions}), so "newer" here means the same thing it means in MVS.
 * A newer version in a different major is reported separately: pinning it
 * would cross a major boundary, which the resolver refuses to do silently.
 */
public final class OutdatedCommand implements Command {

    private final Path projectDir;

    public OutdatedCommand(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "outdated";
    }

    @Override
    public String summary() {
        return "list pinned dependencies that have newer versions available";
    }

    @Override
    public String usage() {
        return "[--json] [--dev]";
    }

    @Override
    public boolean supportsOffline() {
        // the whole answer comes from repositories
        return false;
    }

    /** One newer version that is the same major as the pin. */
    private record Update(String id, String pinned, String available, boolean major) {}

    /** A row nobody can compare, with a short machine label and the human text. */
    private record Skipped(String id, String reason, String detail) {}

    /** A lookup that failed. */
    private record Failed(String id, String error) {}

    @Override
    public Command.Category category() {
        return Command.Category.INSPECT;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.ALWAYS)
                .workspace(CommandCapabilities.WorkspaceMode.MODULE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        boolean json = args.contains("--json");
        if (json && Workspaces.rootAt(projectDir).isPresent() && !hasModuleOption(args)) {
            JsonResult.error("outdated", 2, "MODULE_REQUIRED",
                    "this directory is a workspace root — say which module: --module <path>")
                    .emit(2);
            return 2;
        }
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new OutdatedCommand(dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean includeDev = false;
        for (String arg : args) {
            switch (arg) {
                case "--json" -> { }
                case "--dev" -> includeDev = true;
                default -> {
                    String message = "outdated takes --json and --dev only — got '" + arg + "'";
                    if (json) {
                        JsonResult.error("outdated", 2, "USAGE", message).emit(2);
                    } else {
                        System.err.println(Ui.errPrefix() + message);
                    }
                    return 2;
                }
            }
        }
        // The lock is the only input: it is what build/run/package consume, and
        // it outlives any particular manifest wording.
        Path lock = projectDir.resolve("mjm.lock");
        if (!Files.exists(lock)) {
            String message = "no mjm.lock in " + projectDir + " — run mjm lock first";
            if (json) {
                JsonResult.error("outdated", 1, "LOCK_MISSING", message).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + message);
            }
            return 1;
        }
        List<LockedArtifact> locked;
        try {
            locked = LockFileReader.read(Files.readString(lock));
        } catch (IOException e) {
            String message = "cannot read mjm.lock in " + projectDir;
            if (json) {
                JsonResult.error("outdated", 1, "LOCK_UNREADABLE", message).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + message);
            }
            return 1;
        } catch (RuntimeException e) {
            if (json) {
                JsonResult.error("outdated", 1, "LOCK_INVALID", e.getMessage()).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + e.getMessage());
            }
            return 1;
        }

        List<Update> updates = new ArrayList<>();
        List<Update> majorJumps = new ArrayList<>();
        List<Skipped> unchecked = new ArrayList<>();
        List<Failed> failures = new ArrayList<>();
        int checked = 0;

        for (LockedArtifact artifact : locked) {
            if (!includeDev && artifact.scope() != com.jujin.mjm.resolve.Scope.MAIN) {
                continue;  // default: production closure only
            }
            String source = artifact.source();
            String reason = ArtifactReasons.of(artifact);
            if (reason.equals("native-release")) {
                unchecked.add(new Skipped(artifact.id(), reason,
                        "native release: the tag is the version"));
                continue;
            }
            if (reason.equals("bare-url")) {
                unchecked.add(new Skipped(artifact.id(), reason, "bare URL: no metadata"));
                continue;
            }
            if (reason.equals("local-repository")) {
                unchecked.add(new Skipped(artifact.id(), reason,
                        "local repository: no version listing"));
                continue;
            }
            if (reason.equals("mjm-repository")) {
                unchecked.add(new Skipped(artifact.id(), reason,
                        "mjm layout: no version index"));
                continue;
            }
            Repository repository;
            try {
                repository = source.equals("central") ? null : Repository.parse(source);
            } catch (IllegalArgumentException unrecognized) {
                // ArtifactReasons already labels this named-repository; do not
                // turn an unknown source into a command failure
                unchecked.add(new Skipped(artifact.id(), "named-repository",
                        "unrecognized source: " + source));
                continue;
            }

            List<String> available;
            try {
                available = MetadataVersions.from(
                        repository == null ? ArtifactLocations.centralBase()
                                : repository.networkBase(),
                        group(artifact.id()), artifactName(artifact.id()));
            } catch (RuntimeException e) {
                failures.add(new Failed(artifact.id(), String.valueOf(e.getMessage())));
                continue;
            }

            if (available.isEmpty()) {
                failures.add(new Failed(artifact.id(), "no versions listed"));
                continue;
            }
            checked++;
            String pinned = artifact.version();
            // A pre-release is not an "update" for someone on a stable version:
            // mjm pins exactly, so picking an alpha is a decision, not a patch.
            // Someone already on a pre-release does see newer ones.
            String newest = newest(available, allowsPrerelease(pinned));
            if (newest == null || Versions.compare(newest, pinned) <= 0) {
                continue;
            }
            Integer pinnedMajor = Versions.majorOf(pinned);
            Integer newestMajor = Versions.majorOf(newest);
            boolean sameMajor = pinnedMajor != null && pinnedMajor.equals(newestMajor);
            (sameMajor ? updates : majorJumps).add(
                    new Update(artifact.id(), pinned, newest, !sameMajor));
        }

        boolean upToDate = checked > 0 && unchecked.isEmpty() && failures.isEmpty()
                && updates.isEmpty() && majorJumps.isEmpty();
        if (json) {
            printJson(updates, majorJumps, unchecked, failures, upToDate);
        } else {
            report(updates, majorJumps, unchecked, failures, upToDate);
        }
        return failures.isEmpty() ? 0 : 1;
    }

    /** One JSON document on stdout; nothing else. */
    private void printJson(List<Update> updates, List<Update> majorJumps,
                           List<Skipped> unchecked, List<Failed> failures,
                           boolean upToDate) {
        var root = com.jujin.freeway.commons.json.JsonUtils.object();
        root.put("schemaVersion", CommandCatalog.SCHEMA_VERSION);
        root.put("kind", "commandResult");
        root.put("command", "outdated");
        root.put("status", "ok");
        root.put("exitCode", 0);
        root.put("dryRun", false);
        root.put("offline", com.jujin.mjm.store.ArtifactStore.isOffline());
        root.put("diagnostics", com.jujin.freeway.commons.json.JsonUtils.array());
        root.put("upToDate", upToDate);

        var updateArray = com.jujin.freeway.commons.json.JsonUtils.array();
        for (Update update : updates) {
            var entry = com.jujin.freeway.commons.json.JsonUtils.object();
            entry.put("id", update.id());
            entry.put("pinned", update.pinned());
            entry.put("available", update.available());
            entry.put("major", update.major());
            updateArray.add(entry);
        }
        for (Update update : majorJumps) {
            var entry = com.jujin.freeway.commons.json.JsonUtils.object();
            entry.put("id", update.id());
            entry.put("pinned", update.pinned());
            entry.put("available", update.available());
            entry.put("major", true);
            updateArray.add(entry);
        }
        root.put("updates", updateArray);

        var skippedArray = com.jujin.freeway.commons.json.JsonUtils.array();
        for (Skipped skipped : unchecked) {
            var entry = com.jujin.freeway.commons.json.JsonUtils.object();
            entry.put("id", skipped.id());
            entry.put("reason", skipped.reason());
            skippedArray.add(entry);
        }
        root.put("notChecked", skippedArray);

        var failedArray = com.jujin.freeway.commons.json.JsonUtils.array();
        for (Failed failure : failures) {
            var entry = com.jujin.freeway.commons.json.JsonUtils.object();
            entry.put("id", failure.id());
            entry.put("error", failure.error());
            failedArray.add(entry);
        }
        root.put("failures", failedArray);

        JsonResult.markEmitted();
        System.out.println(root);
    }

    private void report(List<Update> updates, List<Update> majorJumps,
                        List<Skipped> unchecked, List<Failed> failures, boolean upToDate) {
        // "up to date" is a claim about the whole closure: it needs a comparison
        // for every row. Nothing checked (all gh:/url/local:), a skipped source,
        // or a failed lookup each make the honest answer the section below.
        if (upToDate) {
            System.out.println(Ui.ok("everything is up to date"));
        }
        if (!updates.isEmpty()) {
            System.out.println(Ui.heading("updates available:"));
            for (Update update : updates) {
                System.out.println("  " + update.id() + "  " + update.pinned()
                        + " → " + update.available());
            }
        }
        if (!majorJumps.isEmpty()) {
            System.out.println();
            System.out.println(Ui.warn(
                    "major version changes (the resolver refuses these silently):"));
            for (Update update : majorJumps) {
                System.out.println("  " + update.id() + "  " + update.pinned()
                        + " → " + update.available());
            }
        }
        if (!unchecked.isEmpty()) {
            System.out.println();
            System.out.println(Ui.dim("not checked:"));
            for (Skipped skipped : unchecked) {
                System.out.println("  " + skipped.id() + " (" + skipped.detail() + ")");
            }
        }
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println(Ui.fail("could not check:"));
            for (Failed failure : failures) {
                System.out.println("  " + failure.id() + " — " + failure.error());
            }
        }
        if (!updates.isEmpty()) {
            System.out.println();
            System.out.println(Ui.dim("to apply: edit mjm.toml, then run mjm lock"));
        }
    }

    /**
     * Pre-release qualifiers are matched as a whole token, never as a substring:
     * the qualifier after the first dash must be one of these markers plus an
     * optional separator and an ordinal ({@code rc1}, {@code m2}, {@code rc-1}).
     * Substring matching made {@code 1.0.0-main} look like an {@code m} release
     * and hid the stable update; a dash alone is not the marker either, so
     * {@code 30.0-jre} and {@code 2.0.0.Final} stay stable.
     */
    private static final java.util.regex.Pattern PRERELEASE = java.util.regex.Pattern.compile(
            "(alpha|beta|rc|m|milestone|preview|early|ea)([-.]?[0-9]+)?");

    /** Whether a pinned version is itself a pre-release ({@code 2.0.0-rc1}). */
    static boolean allowsPrerelease(String version) {
        int dash = version.indexOf('-');
        if (dash < 0 || dash == version.length() - 1) {
            return false;
        }
        String qualifier = version.substring(dash + 1).toLowerCase(java.util.Locale.ROOT);
        return PRERELEASE.matcher(qualifier).matches();
    }

    /** Whether a candidate version is a pre-release. */
    static boolean isPrerelease(String version) {
        return allowsPrerelease(version);
    }

    /**
     * The highest listed version, by the resolver's own ordering. Snapshots are
     * never candidates (mjm does not resolve them), and pre-releases are only
     * candidates when the caller is already on one.
     */
    static String newest(List<String> versions, boolean includePrerelease) {
        String best = null;
        for (String candidate : versions) {
            if (candidate.toLowerCase(java.util.Locale.ROOT).endsWith("-snapshot")) {
                continue;  // mjm does not resolve snapshots
            }
            if (!includePrerelease && isPrerelease(candidate)) {
                continue;  // an alpha is not a routine update
            }
            if (best == null || Versions.compare(candidate, best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static String group(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }

    /**
     * The artifact id without a classifier: {@code group:artifact:classifier} is
     * one identity in the lock, but {@code maven-metadata.xml} lives under the
     * base artifact — asking for {@code artifact:classifier/maven-metadata.xml}
     * is a 404, not a missing version.
     */
    private static String artifactName(String id) {
        int first = id.indexOf(':');
        if (first < 0) {
            return id;
        }
        int second = id.indexOf(':', first + 1);
        return second < 0 ? id.substring(first + 1) : id.substring(first + 1, second);
    }
    private static boolean hasModuleOption(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("--module") || arg.startsWith("--module="));
    }

}
