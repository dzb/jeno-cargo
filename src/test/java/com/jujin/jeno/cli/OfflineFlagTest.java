package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code -o} and {@code --offline} mean the same thing, including for network-only commands. */
class OfflineFlagTest {

    @AfterEach
    void resetOffline() {
        Substitutions.useLocalOverrides(true);
        try {
            var field = ArtifactStore.class.getDeclaredField("offline");
            field.setAccessible(true);
            field.set(null, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reset the offline flag", e);
        }
    }

    @Test
    void repositoryInsecureOptInIsTheOnlyLocalTokenException() {
        if (System.getenv("JENO_ALLOW_INSECURE_TOKEN") == null) {
            assertFalse(JenoUploads.tokenMayCrossCleartext(
                    "http://192.168.1.39:8080/", false));
        }
        assertTrue(JenoUploads.tokenMayCrossCleartext(
                "http://192.168.1.39:8080/", true));
        assertTrue(JenoUploads.tokenMayCrossCleartext(
                "https://repo.example/", false));
        assertTrue(JenoUploads.tokenMayCrossCleartext(
                "http://127.0.0.1:8080/", false));
    }

    @Test
    void lockedFlagIsConsumedAndDisablesWorkspaceOverrides() {
        AtomicBoolean localOverrides = new AtomicBoolean();
        AtomicBoolean forwarded = new AtomicBoolean();
        Command probe = new Command() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public String summary() {
                return "a test command";
            }

            @Override
            public CommandCapabilities capabilities() {
                return CommandCapabilities.builder().build();
            }

            @Override
            public int execute(List<String> args) {
                localOverrides.set(Substitutions.localOverridesEnabled());
                forwarded.set(args.contains("--locked"));
                return 0;
            }
        };
        CommandRunner runner = new CommandRunner(List.of(probe));

        assertEquals(0, runner.run("--locked", "probe"));
        assertFalse(localOverrides.get(), "global --locked disables local substitutions");
        assertFalse(forwarded.get(), "the runner consumes --locked");

        assertEquals(0, runner.run("probe", "--locked"));
        assertFalse(localOverrides.get(), "the command-local spelling works too");

        assertEquals(0, runner.run("probe"));
        assertTrue(localOverrides.get(), "a later invocation starts in normal mode again");
    }

    @Test
    void bothSpellingsTurnOfflineOnAndAreStripped() {
        AtomicBoolean sawOffline = new AtomicBoolean();
        AtomicBoolean sawFlag = new AtomicBoolean();
        Command probe = new Command() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public String summary() {
                return "a test command";
            }

            @Override
            public CommandCapabilities capabilities() {
                return CommandCapabilities.builder().build();
            }

            @Override
            public int execute(List<String> args) {
                sawOffline.set(ArtifactStore.isOffline());
                sawFlag.set(args.contains("-o") || args.contains("--offline"));
                return 0;
            }
        };
        CommandRunner runner = new CommandRunner(List.of(probe));

        assertEquals(0, runner.run("probe", "-o"));
        assertTrue(sawOffline.get(), "-o means offline");
        assertFalse(sawFlag.get(), "the flag is consumed, not forwarded");

        resetOffline();
        assertEquals(0, runner.run("probe", "--offline", "-o"));
        assertTrue(sawOffline.get(), "repeating both spellings is not an error");
    }

    @Test
    void updateRefusesTheShortFlagToo() {
        PrintStream original = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new CommandRunner(List.of(new UpdateCommand())).run("update", "-o");
        } finally {
            System.setErr(original);
        }

        assertEquals(2, exit);
        assertTrue(errors.toString(StandardCharsets.UTF_8)
                .contains("needs the network by design"), errors.toString());
    }
}
