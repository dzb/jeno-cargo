package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
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

            import com.jujin.jeno.test.JenoAssert;
            import com.jujin.jeno.test.JenoTest;

            public class MjTests {
                @JenoTest
                void passes() { JenoAssert.equals("a", "a"); }

                @JenoTest
                void fails() { JenoAssert.equals("expected-text", "actual-text"); }
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
    void jsonTestReportsSummaryAndXmlPaths(@TempDir Path storeDir) throws Exception {
        project();
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.jeno.test.JenoAssert;
            import com.jujin.jeno.test.JenoTest;

            public class Tests {
                @JenoTest
                void passes() { JenoAssert.equals("a", "a"); }

                @JenoTest
                void fails() { JenoAssert.equals("expected", "actual"); }
            }
            """);
        lock();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            try (var central = VendoredToolchain.start()) {
                exit = new TestCommand(new ArtifactStore(storeDir), dir)
                        .execute(List.of("--json"));
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(1, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("FAIL"),
                "test process logs go to stderr, not JSON stdout");
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("test", json.getString("command"));
        assertEquals("failed", json.getString("status"));
        var result = json.getArray("modules").getObject(0).getObject("result");
        assertEquals(2, result.getInt("tests"));
        assertEquals(1, result.getInt("passed"));
        assertEquals(1, result.getInt("failed"));
        assertEquals(0, result.getInt("skipped"));
        assertEquals("junit-xml", result.getString("reportFormat"));
        assertEquals(1, result.getArray("reportFiles").size());
        assertTrue(Files.isRegularFile(Path.of(
                result.getArray("reportFiles").getString(0))));
        assertEquals("TEST_FAILED",
                json.getArray("diagnostics").getObject(0).getString("code"));
    }

    @Test
    void twoRunsAreStructurallyIdenticalExceptForTime(@TempDir Path storeDir) throws Exception {
        project();
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.jeno.test.JenoTest;

            public class Tests {
                @JenoTest
                void one() { }

                @JenoTest
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

            import com.jujin.jeno.test.JenoBeforeEach;
            import com.jujin.jeno.test.JenoTest;

            public class Tests {
                @JenoBeforeEach
                void setUp() { throw new IllegalStateException("setup broke"); }

                @JenoTest
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

    @Test
    void anEmptySuiteStillClearsThePreviousResults(@TempDir Path storeDir) throws Exception {
        project();
        Path testSource = dir.resolve("src/test/java/demo/Tests.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, """
            package demo;

            import com.jujin.jeno.test.JenoTest;

            public class Tests {
                @JenoTest
                void one() { }
            }
            """);
        lock();
        ArtifactStore store = new ArtifactStore(storeDir);
        Path reports = dir.resolve("target/test-results");
        try (var central = VendoredToolchain.start()) {
            assertEquals(0, new TestCommand(store, dir).execute(List.of()));
            assertTrue(Files.exists(reports.resolve("TEST-demo.Tests.xml")), "first run wrote XML");

            Files.delete(testSource);

            assertEquals(0, new TestCommand(store, dir).execute(List.of()),
                    "no tests is still a success");
        }

        assertTrue(emptyDirectory(reports), "the previous run's XML is gone");
    }

    @Test
    void aCompileFailureLeavesTheResultsEmpty(@TempDir Path storeDir) throws Exception {
        project();
        Path testSource = dir.resolve("src/test/java/demo/Tests.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, """
            package demo;

            import com.jujin.jeno.test.JenoTest;

            public class Tests {
                @JenoTest
                void one() { }
            }
            """);
        lock();
        ArtifactStore store = new ArtifactStore(storeDir);
        Path reports = dir.resolve("target/test-results");
        try (var central = VendoredToolchain.start()) {
            assertEquals(0, new TestCommand(store, dir).execute(List.of()));
            assertTrue(Files.exists(reports.resolve("TEST-demo.Tests.xml")), "first run wrote XML");

            Files.writeString(testSource, "package demo; public class Tests { this is not java }");

            assertEquals(1, new TestCommand(store, dir).execute(List.of()));
        }

        assertTrue(emptyDirectory(reports), "a failed compile leaves no results from this run");
    }

    private static boolean emptyDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return true;
        }
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    @Test
    void aProcessorRunsFromTheProcessorPathNotTheTestClasspath(@TempDir Path storeDir)
            throws Exception {
        project();
        Path testSource = dir.resolve("src/test/java/demo/Tests.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, """
            package demo;

            import com.jujin.jeno.test.JenoTest;

            public class Tests {
                @JenoTest
                void one() { }
            }
            """);
        Path processorJar = explodingProcessor(dir.resolve("processor"));
        String sha = ArtifactStore.sha256(processorJar);
        Files.createDirectories(storeDir);
        Files.copy(processorJar, storeDir.resolve(sha + ".jar"));
        Files.writeString(dir.resolve("mjm.lock"), """
            # generated by mjm
            [[artifacts]]
            id = "org.example:exploding-processor"
            version = "1.0.0"
            source = "central"
            url = "https://example.invalid/exploding-processor-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            scope = "processor"
            """.formatted(sha));

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());
        }

        assertEquals(1, exit,
                "the processor ran (and threw) because it was on -processorpath; "
                        + "it is not on the test classpath");
    }

    /**
     * A jar whose service-registered processor always throws. If javac does not
     * see it on -processorpath, nothing happens and the compile succeeds; if it
     * does, the compile fails — the observable difference the test needs.
     */
    private static Path explodingProcessor(Path work) throws Exception {
        Path source = work.resolve("src/p/Explode.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package p;

            import java.util.Set;
            import javax.annotation.processing.AbstractProcessor;
            import javax.annotation.processing.RoundEnvironment;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;

            public final class Explode extends AbstractProcessor {
                @Override
                public Set<String> getSupportedAnnotationTypes() {
                    return Set.of("*");
                }

                @Override
                public SourceVersion getSupportedSourceVersion() {
                    return SourceVersion.latestSupported();
                }

                @Override
                public boolean process(Set<? extends TypeElement> annotations,
                                       RoundEnvironment round) {
                    throw new RuntimeException("processor-ran");
                }
            }
            """);
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        int compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertEquals(0, compiled);

        Path jar = work.resolve("exploding.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            try (var walk = Files.walk(classes)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(
                            classes.relativize(file).toString().replace('\\', '/')));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
            zip.putNextEntry(new java.util.zip.ZipEntry(
                    "META-INF/services/javax.annotation.processing.Processor"));
            zip.write("p.Explode\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
}
