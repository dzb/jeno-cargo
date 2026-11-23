package com.jujin.jeno.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * XDG first, legacy {@code ~/.jeno} when it already exists — the compatibility
 * rule that keeps an upgrade from re-downloading a store.
 */
class PathsTest {

    @TempDir
    Path home;

    @Test
    void aFreshHomeUsesTheXdgLocations() throws Exception {
        Path cache = Files.createDirectories(home.resolve("xdg-cache"));
        Path state = Files.createDirectories(home.resolve("xdg-state"));

        var layout = Paths.resolve(
                Map.of("XDG_CACHE_HOME", cache.toString(), "XDG_STATE_HOME", state.toString()),
                home);

        assertEquals(cache.resolve("jeno/store"), layout.store());
        assertEquals(cache.resolve("jeno/sources"), layout.sources());
        assertEquals(state.resolve("jeno/locks"), layout.locks());
    }

    @Test
    void withoutTheVariablesTheDefaultsAreTheXdgOnes() {
        var layout = Paths.resolve(Map.of(), home);

        assertEquals(home.resolve(".cache/jeno/store"), layout.store());
        assertEquals(home.resolve(".cache/jeno/sources"), layout.sources());
        assertEquals(home.resolve(".local/state/jeno/locks"), layout.locks());
    }

    @Test
    void anExistingLegacyDirectoryKeepsBeingUsed() throws Exception {
        Path cache = Files.createDirectories(home.resolve("xdg-cache"));
        Path state = Files.createDirectories(home.resolve("xdg-state"));
        Files.createDirectories(home.resolve(".jeno/store"));
        Files.createDirectories(home.resolve(".jeno/locks"));

        var layout = Paths.resolve(
                Map.of("XDG_CACHE_HOME", cache.toString(), "XDG_STATE_HOME", state.toString()),
                home);

        assertEquals(home.resolve(".jeno/store"), layout.store(), "the old store wins");
        assertEquals(cache.resolve("jeno/sources"), layout.sources(),
                "a directory that does not exist yet moves to XDG");
        assertEquals(home.resolve(".jeno/locks"), layout.locks(), "the old locks win");
    }
}
