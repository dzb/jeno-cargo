package com.jujin.mjm.cli;

import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectReader;
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
 * <p>Only Central artifacts can be checked. A {@code gh:}/{@code gitee:}
 * release carries no version index — the tag is the version — and a bare URL
 * leaf has no metadata at all. Those are listed as skipped rather than
 * silently omitted, because "no update found" and "cannot know" are different
 * answers.
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
    public boolean supportsOffline() {
        // the whole answer comes from repositories
        return false;
    }

    @Override
    public int execute(List<String> args) {
        if (!args.isEmpty()) {
            System.err.println("mjm: outdated takes no arguments");
            return 2;
        }
        MjProject project;
        Path lock = projectDir.resolve("mjm.lock");
        List<LockedArtifact> locked;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
            if (!Files.exists(lock)) {
                System.err.println("mjm: no mjm.lock in " + projectDir + " — run mjm lock first");
                return 1;
            }
            locked = LockFileReader.read(Files.readString(lock));
        } catch (IOException e) {
            System.err.println("mjm: cannot read the project files in " + projectDir
                    + " — run mjm init?");
            return 1;
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }

        List<String> updates = new ArrayList<>();
        List<String> majorJumps = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (LockedArtifact artifact : locked) {
            String source = artifact.source();
            if (source.startsWith("gh:") || source.startsWith("gitee:")) {
                unchecked.add(artifact.id() + " (mj-native: the tag is the version)");
                continue;
            }
            if (source.equals("url")) {
                unchecked.add(artifact.id() + " (bare URL: no metadata)");
                continue;
            }

            if (source.startsWith("local:") || source.startsWith("file:")) {
                unchecked.add(artifact.id() + " (local repository: no version listing)");
                continue;
            }

            List<String> available;
            try {
                available = MetadataVersions.from(
                        source.equals("central") ? ArtifactLocations.centralBase() : source,
                        group(artifact.id()), artifactName(artifact.id()));
            } catch (RuntimeException e) {
                failures.add(artifact.id() + " — " + e.getMessage());
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
            String line = artifact.id() + "  " + pinned + " → " + newest;
            (sameMajor ? updates : majorJumps).add(line);
        }

        report(updates, majorJumps, unchecked, failures, checked);
        return failures.isEmpty() ? 0 : 1;
    }

    private void report(List<String> updates, List<String> majorJumps,
                        List<String> unchecked, List<String> failures, int checked) {
        // "up to date" is a claim about a comparison that happened. With nothing
        // checked (every source is gh:/url/local:) or a failed lookup, the honest
        // answer is the "not checked" / "could not check" section below.
        if (checked > 0 && updates.isEmpty() && majorJumps.isEmpty() && failures.isEmpty()) {
            System.out.println("everything is up to date");
        }
        if (!updates.isEmpty()) {
            System.out.println("updates available:");
            for (String line : updates) {
                System.out.println("  " + line);
            }
        }
        if (!majorJumps.isEmpty()) {
            System.out.println();
            System.out.println("major version changes (the resolver refuses these silently):");
            for (String line : majorJumps) {
                System.out.println("  " + line);
            }
        }
        if (!unchecked.isEmpty()) {
            System.out.println();
            System.out.println("not checked:");
            for (String line : unchecked) {
                System.out.println("  " + line);
            }
        }
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("could not check:");
            for (String line : failures) {
                System.out.println("  " + line);
            }
        }
        if (!updates.isEmpty()) {
            System.out.println();
            System.out.println("to apply: edit mjm.toml, then run mjm lock");
        }
    }

    /**
     * Pre-release qualifiers. A dash alone is not the marker: {@code 30.0-jre}
     * and {@code 2.0.0.Final} are stable releases that happen to carry a
     * suffix, and treating them as pre-releases would hide real updates.
     */
    private static final List<String> PRERELEASE_MARKERS = List.of(
            "-alpha", "-beta", "-rc", "-m", "-milestone", "-preview", "-early", "-ea");

    /** Whether a pinned version is itself a pre-release ({@code 2.0.0-rc1}). */
    static boolean allowsPrerelease(String version) {
        String lower = version.toLowerCase(java.util.Locale.ROOT);
        for (String marker : PRERELEASE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
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
            if (candidate.endsWith("-SNAPSHOT")) {
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

    private static String artifactName(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
