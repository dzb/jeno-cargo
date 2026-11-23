package com.jujin.mjm.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The terminal-output contract: colour when asked, plain text everywhere else. */
class UiTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty("mjm.color");
    }

    @Test
    void forcedColourUsesGlyphsAndAnsi() {
        System.setProperty("mjm.color", "always");

        assertTrue(Ui.enabled());
        assertTrue(Ui.ok("built").contains("✓"));
        assertTrue(Ui.fail("failed").contains("✗"));
        assertTrue(Ui.ok("built").contains("\u001B["));
        assertTrue(Ui.heading("Project").contains("\u001B[1m"));
        assertTrue(Ui.duration(System.nanoTime() - 2_000_000L).contains("ms"));
    }

    @Test
    void capturedStdoutNeverGetsAnsiUnlessColourIsForced() {
        System.clearProperty("mjm.color");
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8));
            assertFalse(Ui.enabled(), "a redirected stream must stay free of ANSI escapes");
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void propertyAlwaysOverridesNoColor() {
        System.setProperty("mjm.color", "always");

        // NO_COLOR may be set in the real environment; the programmatic
        // property is an explicit override, not an environment choice.
        assertTrue(Ui.enabled());
    }

    @Test
    void mjmColorAlwaysAloneEnablesColour() {
        assertTrue(Ui.enabled("always", false));
    }

    @Test
    void noColorOverridesMjmColorAlways() {
        assertFalse(Ui.enabled("always", true));
    }

    @Test
    void neverKeepsTheExistingPlainText() {
        System.setProperty("mjm.color", "never");

        assertFalse(Ui.enabled());
        assertEquals("built", Ui.ok("built"));
        assertEquals("failed", Ui.fail("failed"));
        assertEquals("mjm: missing", Ui.error("missing"));
        assertEquals("mjm: ", Ui.errPrefix());
        assertEquals("ok: present", Ui.check("present"));
        assertEquals("", Ui.duration(System.nanoTime() - 2_000_000L));
        assertEquals("error: broken", Ui.cross("broken"));
    }
}
