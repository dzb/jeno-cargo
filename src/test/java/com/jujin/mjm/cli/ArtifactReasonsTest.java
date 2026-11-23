package com.jujin.mjm.cli;

import com.jujin.mjm.resolve.Scope;
import com.jujin.mjm.store.LockedArtifact;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** outdated and audit describe the same lock row with the same word. */
class ArtifactReasonsTest {

    @Test
    void theVocabularyIsSharedAndComplete() {
        assertEquals("native-release", reason("gh:acme/lib", "https://github.com/acme/lib/x.jar"));
        assertEquals("bare-url", reason("url", "https://host/x.jar"));
        assertEquals("mjm-repository", reason("mjm:https://host/", "https://host/a/1/a-1.jar"));
        assertEquals("mjm-repository", reason("mjm:local:../vendor", "file:vendor/a/1/a-1.jar"));
        assertEquals("local-repository", reason("local:../vendor", "file:vendor/a/1/a-1.jar"));
        assertEquals("named-repository",
                reason("https://nexus.corp/repository/maven/", "https://nexus.corp/…"));
        assertEquals("named-repository", reason("something-unparseable", "https://host/x.jar"));
    }

    private static String reason(String source, String url) {
        return ArtifactReasons.of(new LockedArtifact(
                "com.example:lib", "1.0.0", source, url, "a".repeat(64), null, 1, Scope.MAIN));
    }
}
