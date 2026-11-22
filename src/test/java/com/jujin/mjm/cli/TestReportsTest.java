package com.jujin.mjm.cli;

import com.jujin.mjm.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mjm test} writes surefire-shaped XML: one file per class, real
 * failures with their stack, skips as skips, and nothing that changes between
 * two runs of the same suite.
 */
class TestReportsTest {

    @TempDir
    Path dir;

    @BeforeEach
    void isolateProjectLocks() {
        ProjectLock.useRoot(dir.resolve(".locks"));
    }

    @AfterEach
    void restoreProjectLocks() {
        ProjectLock.useUserRoot();
    }

    @Test
    void bothRunnersReportEveryCaseWithItsOutcome(@TempDir Path storeDir) throws Exception {
        project();
        Files.writeString(dir.resolve("src/test/java/demo/MjTests.java"), """
            package demo;

            import com.jujin.mjm.test.MjmAssert;
            import com.jujin.mjm.test.MjmTest;

            public class MjTests {
                @MjmTest
                void passes() { MjmAssert.equals("a", "a"); }

                @MjmTest
                void fails() { MjmAssert.equals("expected-text", "actual-text"); }
            }
            """);
        Files.writeString(dir.resolve("src/test/java/demo/JupiterTests.java"), """
            package demo;

            import org.junit.jupiter.api.Disabled;
            import org.junit.jupiter.api.Test;

            public class JupiterTests {
                @Test
                void passes() { }

                @Disabled("not today")
                @Test
                void skipped() { }
            }
            """);
        lock();

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());
        }

        assertEquals(1, exit, "the failing @MjmTest fails the command");
        Path reports = dir.resolve("target/test-results");

        Document mj = parse(reports.resolve("TEST-demo.MjTests.xml"));
        assertEquals("2", mj.getDocumentElement().getAttribute("tests"));
        assertEquals("1", mj.getDocumentElement().getAttribute("failures"));

        Element failure = first(mj, "failure");
        assertTrue(failure.getAttribute("message").contains("expected-text"),
                failure.getAttribute("message"));
        assertTrue(failure.getAttribute("type").contains("AssertionError"),
                failure.getAttribute("type"));
        assertTrue(failure.getTextContent().contains("at demo.MjTests.fails"),
                "the stack is in the report: " + failure.getTextContent());

        Document jupiter = parse(reports.resolve("TEST-demo.JupiterTests.xml"));
        assertEquals("2", jupiter.getDocumentElement().getAttribute("tests"));
        assertEquals("0", jupiter.getDocumentElement().getAttribute("failures"));
        assertEquals("1", jupiter.getDocumentElement().getAttribute("skipped"));
    }

    @Test
    void twoRunsAreStructurallyIdenticalExceptForTime(@TempDir Path storeDir) throws Exception {
        project();
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.mjm.test.MjmTest;

            public class Tests {
                @MjmTest
                void one() { }

                @MjmTest
                void two() { }
            }
            """);
        lock();
        ArtifactStore store = new ArtifactStore(storeDir);
        Path reports = dir.resolve("target/test-results");
        Document first;
        Document second;
        String raw;
        try (var central = VendoredToolchain.start()) {
            assertEquals(0, new TestCommand(store, dir).execute(List.of()));
            first = parse(reports.resolve("TEST-demo.Tests.xml"));
            raw = Files.readString(reports.resolve("TEST-demo.Tests.xml"));
            assertEquals(0, new TestCommand(store, dir).execute(List.of()));
            second = parse(reports.resolve("TEST-demo.Tests.xml"));
        }

        assertTrue(!raw.contains("timestamp="), "no timestamp attribute: " + raw);
        assertEquals(first.getDocumentElement().getAttribute("tests"),
                second.getDocumentElement().getAttribute("tests"));
        var firstCases = first.getElementsByTagName("testcase");
        var secondCases = second.getElementsByTagName("testcase");
        assertEquals(firstCases.getLength(), secondCases.getLength());
        for (int i = 0; i < firstCases.getLength(); i++) {
            Element a = (Element) firstCases.item(i);
            Element b = (Element) secondCases.item(i);
            assertEquals(a.getAttribute("name"), b.getAttribute("name"),
                    "apart from time, the two runs must be identical");
            assertEquals(a.getAttribute("classname"), b.getAttribute("classname"));
        }
        // the only attribute allowed to differ is the real duration
        String firstNormalized = raw.replaceAll("time=\"[0-9.]+\"", "time");
        assertEquals(firstNormalized, Files.readString(reports.resolve("TEST-demo.Tests.xml"))
                        .replaceAll("time=\"[0-9.]+\"", "time"),
                "apart from time, the two runs must be identical");
    }

    @Test
    void aSkippedOnlySuiteIsNotAFailure(@TempDir Path storeDir) throws Exception {
        project();
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import org.junit.jupiter.api.Disabled;
            import org.junit.jupiter.api.Test;

            public class Tests {
                @Disabled("later")
                @Test
                void skipped() { }
            }
            """);
        lock();

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());
        }

        assertEquals(0, exit);
        Document report = parse(dir.resolve("target/test-results/TEST-demo.Tests.xml"));
        assertEquals("1", report.getDocumentElement().getAttribute("skipped"));
        assertEquals("0", report.getDocumentElement().getAttribute("failures"));
    }

    @Test
    void aFailingBeforeEachBelongsToItsCase(@TempDir Path storeDir) throws Exception {
        project();
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.mjm.test.MjmBeforeEach;
            import com.jujin.mjm.test.MjmTest;

            public class Tests {
                @MjmBeforeEach
                void setUp() { throw new IllegalStateException("setup broke"); }

                @MjmTest
                void passesIfSetupWorks() { }
            }
            """);
        lock();

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());
        }

        assertEquals(1, exit, "the case fails with its fixture");
        Document report = parse(dir.resolve("target/test-results/TEST-demo.Tests.xml"));
        assertEquals("1", report.getDocumentElement().getAttribute("tests"));
        assertEquals("1", report.getDocumentElement().getAttribute("failures"));
        Element failure = first(report, "failure");
        assertTrue(failure.getAttribute("message").contains("setup broke"),
                failure.getAttribute("message"));
        assertTrue(failure.getTextContent().contains("at demo.Tests.setUp"),
                failure.getTextContent());
    }

    private void lock() throws IOException {
        Files.writeString(dir.resolve("mjm.lock"), "# generated by mjm\n");
    }

    private void project() throws IOException {
        Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
    }

    private static Document parse(Path file) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                Files.readAllBytes(file)));
    }

    private static Element first(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        assertEquals(true, nodes.getLength() > 0, "expected a <" + tag + ">");
        return (Element) nodes.item(0);
    }
}
