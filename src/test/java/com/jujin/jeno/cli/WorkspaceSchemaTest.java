package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workspace file is validated on read: positions in errors, no duplicate ids. */
class WorkspaceSchemaTest {

    @TempDir
    Path root;

    private Path moduleA;
    private Path moduleB;

    @BeforeEach
    void setUp() throws Exception {
        ProjectLock.useRoot(root.resolve(".locks"));
        com.jujin.jeno.store.Paths.useForTesting(root.resolve(".cache/store"),
                root.resolve(".cache/sources"), root.resolve(".locks"));
        moduleA = root.resolve("libs/a");
        moduleB = root.resolve("libs/b");
        module(moduleA, "shared", "com.example", "1.0.0");
        module(moduleB, "shared", "com.example", "1.0.0");
    }

    @AfterEach
    void tearDown() {
        ProjectLock.useUserRoot();
        com.jujin.jeno.store.Paths.resetForTesting();
    }

    @Test
    void twoModulesClaimingOneIdAreRejectedEvenByRootLock() throws IOException {
        Files.writeString(root.resolve("mjm.ws.toml"), """
            [ws]
            modules = ["libs/a", "libs/b"]
            """);
        ArtifactStore store = new ArtifactStore(root.resolve(".cache/store"));
        LockCommand lock = new LockCommand(
                new LockService(store, new StorePomSource(store), new StoreDescriptorSource(store)),
                root);

        Result result = run(lock);

        assertEquals(1, result.exit(), "the file itself is invalid, not a usage error");
        assertTrue(result.err().contains("two modules claim 'com.example:shared'"), result.err());
        assertTrue(result.err().contains("libs/a") && result.err().contains("libs/b"),
                result.err());
    }

    @Test
    void schemaErrorsCarryLineAndColumn() throws IOException {
        Files.writeString(root.resolve("mjm.ws.toml"), """
            [ws]
            modules = ["libs/a"]
            junk = 1
            """);

        var error = assertThrows(Workspaces.WorkspaceException.class,
                () -> Workspaces.read(root));

        assertTrue(error.getMessage().contains("mjm.ws.toml:3:1"), error.getMessage());
        assertTrue(error.getMessage().contains("[ws].junk"), error.getMessage());
    }

    @Test
    void anUnknownSectionNamesItsOwnLine() throws IOException {
        Files.writeString(root.resolve("mjm.ws.toml"), """
            [other]
            modules = ["libs/a"]
            """);

        var error = assertThrows(Workspaces.WorkspaceException.class,
                () -> Workspaces.read(root));

        assertTrue(error.getMessage().contains("mjm.ws.toml:1:1"), error.getMessage());
        assertTrue(error.getMessage().contains("[other]"), error.getMessage());
    }

    // ------------------------------------------------------------------

    private static void module(Path dir, String name, String group, String version)
            throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "%s"
            group = "%s"
            version = "%s"
            """.formatted(name, group, version));
    }

    private static Result run(Command command) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = command.execute(List.of());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}
}
