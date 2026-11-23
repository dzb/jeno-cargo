package com.jujin.mjm.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code mjm init --pom} on a multi-module root writes nothing and says why. */
class InitPomTest {

    @TempDir
    Path dir;

    @Test
    void aMultiModuleRootIsRefusedAndExplained() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <modules>
                <module>app</module>
                <module>lib</module>
              </modules>
            </project>
            """);

        Result result = run();

        assertEquals(1, result.exit());
        assertFalse(Files.exists(dir.resolve("mjm.toml")), "the root did not become a module");
        assertTrue(result.err().contains("multi-module pom root"), result.err());
        assertTrue(result.err().contains("app, lib"), result.err());
        assertTrue(result.err().contains("mjm init --ws"), result.err());
    }

    private Result run() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new InitCommand(dir).execute(List.of("--pom"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}
}
