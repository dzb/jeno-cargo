package com.jujin.jeno.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryHelpTest {

    @Test
    void publishAndReleaseExplainTheInsecureTokenOptIn(@TempDir Path dir) {
        for (Command command : java.util.List.of(
                new PublishCommand(dir), new ReleaseCommand(dir))) {
            String help = command.help();
            assertTrue(help.contains("insecure = true"), command.name());
            assertTrue(help.contains("type = \"jeno\""), command.name());
            assertTrue(help.contains("literal private-network http:// address"),
                    command.name());
            assertTrue(help.contains("JENO_PUBLISH_TOKEN"), command.name());
            assertTrue(help.contains("not stored in jeno.lock"), command.name());
            assertTrue(help.contains("jeno publish mine"), command.name());
            assertTrue(help.contains("JENO_ALLOW_INSECURE_TOKEN=1"), command.name());
        }
    }
}
