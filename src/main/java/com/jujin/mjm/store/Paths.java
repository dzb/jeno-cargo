package com.jujin.mjm.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where mjm keeps the things that are not the project: the content-addressed
 * store, extracted source trees, and the per-project lock files.
 *
 * <pre>
 *   store   ${XDG_CACHE_HOME:-~/.cache}/mjm/store
 *   sources ${XDG_CACHE_HOME:-~/.cache}/mjm/sources
 *   locks   ${XDG_STATE_HOME:-~/.local/state}/mjm/locks
 * </pre>
 *
 * <p>A directory that already exists under the pre-XDG {@code ~/.mjm} keeps
 * being used — no moving, no error — so an upgrade never re-downloads a store
 * or strands a project lock. New machines start in the XDG locations.
 */
public final class Paths {

    /** The three roots, already decided. */
    public record Layout(Path store, Path sources, Path locks) {}

    private static final Path LEGACY = Path.of(System.getProperty("user.home"), ".mjm");

    private static final Layout LAYOUT = resolve(System.getenv(),
            Path.of(System.getProperty("user.home")));

    /** Tests replace the roots; nothing else writes this. */
    private static volatile Layout override;

    static {
        List<String> legacy = new ArrayList<>();
        if (LAYOUT.store().startsWith(LEGACY) || LAYOUT.sources().startsWith(LEGACY)) {
            legacy.add("store/sources");
        }
        if (LAYOUT.locks().startsWith(LEGACY)) {
            legacy.add("locks");
        }
        if (!legacy.isEmpty()) {
            System.err.println("mjm: still using the old ~/.mjm " + String.join(" and ", legacy)
                    + " — run `mjm cache purge` once (alias: clean) to move store/sources to the XDG "
                    + "location (locks move after the old directory is removed)");
        }
    }

    private Paths() {}

    /** The same decision, from an explicit environment and home — the seam tests use. */
    static Layout resolve(Map<String, String> env, Path home) {
        Path legacy = home.resolve(".mjm");
        Path cache = cacheHome(env, home).resolve("mjm");
        Path state = stateHome(env, home).resolve("mjm");
        return new Layout(
                choose(legacy.resolve("store"), cache.resolve("store")),
                choose(legacy.resolve("sources"), cache.resolve("sources")),
                choose(legacy.resolve("locks"), state.resolve("locks")));
    }

    private static Path choose(Path legacy, Path modern) {
        return Files.exists(legacy) ? legacy : modern;
    }

    private static Path cacheHome(Map<String, String> env, Path home) {
        String xdg = env.get("XDG_CACHE_HOME");
        return xdg == null || xdg.isBlank() ? home.resolve(".cache") : Path.of(xdg);
    }

    private static Path stateHome(Map<String, String> env, Path home) {
        String xdg = env.get("XDG_STATE_HOME");
        return xdg == null || xdg.isBlank() ? home.resolve(".local/state") : Path.of(xdg);
    }

    public static Path store() {
        Layout layout = override != null ? override : LAYOUT;
        return layout.store();
    }

    public static Path sources() {
        Layout layout = override != null ? override : LAYOUT;
        return layout.sources();
    }

    public static Path locks() {
        Layout layout = override != null ? override : LAYOUT;
        return layout.locks();
    }

    /** Tests only: point the three roots at scratch directories. */
    public static void useForTesting(Path store, Path sources, Path locks) {
        override = new Layout(store, sources, locks);
    }

    public static void resetForTesting() {
        override = null;
    }
}
