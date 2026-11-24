package com.jujin.jeno.cli;

import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five modes, against one fixture that has all three scopes: a MAIN
 * dependency, a DEV dependency, and a PROCESSOR. Their entry sets and order are
 * the ones the four old call sites produced.
 */
class ClasspathAssemblerTest {

    @TempDir
    Path dir;

    private com.sun.net.httpserver.HttpServer central;
    private ArtifactStore store;
    private JenoProject project;
    private List<LockedArtifact> locked;

    @BeforeEach
    void setUp() throws Exception {
        com.jujin.jeno.store.Paths.useForTesting(dir.resolve("store"),
                dir.resolve("sources"), dir.resolve("locks"));
        ProjectLock.useRoot(dir.resolve("locks"));
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(dir);

        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("target/test-classes"));
        writeArtifact("com.example", "lib-main", "1.0.0", "");
        writeArtifact("com.example", "lib-dev", "1.0.0", "");
        // a processor is addressed as group:artifact:version and resolves from
        // Central, so the fixture serves it from the loopback
        central = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        byte[] procPom = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>lib-proc</artifactId>
              <version>1.0.0</version>
            </project>
            """.getBytes();
        byte[] procJar = "proc".getBytes();
        central.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.endsWith(".pom") ? procPom
                    : path.endsWith(".jar") ? procJar : null;
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        central.start();
        System.setProperty("jeno.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");

        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            vendor = "local:vendor"

            [deps.vendor]
            "com.example:lib-main" = "1.0.0"

            [dev-deps.vendor]
            "com.example:lib-dev" = "1.0.0"

            [build]
            processors = ["com.example:lib-proc:1.0.0"]
            """);
        store = new ArtifactStore(dir.resolve("store"));
        new LockService(store, new StorePomSource(store), new StoreDescriptorSource(store))
                .refresh(dir);
        project = ProjectReader.parse(Files.readString(dir.resolve("jeno.toml")));
        locked = LockFileReader.read(Files.readString(dir.resolve("jeno.lock")));
    }

    @AfterEach
    void tearDown() {
        if (central != null) {
            central.stop(0);
        }
        System.clearProperty("jeno.central.base");
        com.jujin.jeno.store.Paths.resetForTesting();
        ProjectLock.useUserRoot();
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void everyModeKeepsItsOldShape() throws Exception {
        List<String> compile = strings(ClasspathAssembler.Mode.COMPILE,
                ClasspathAssembler.Input.compile(project, locked));
        List<String> test = strings(ClasspathAssembler.Mode.TEST,
                ClasspathAssembler.Input.test(locked, List.of(),
                        dir.resolve("target/classes"), true));
        List<String> run = strings(ClasspathAssembler.Mode.RUN,
                ClasspathAssembler.Input.run(locked));
        List<String> exportMain = strings(ClasspathAssembler.Mode.EXPORT_MAIN,
                ClasspathAssembler.Input.export(project, locked));
        List<String> exportTest = strings(ClasspathAssembler.Mode.EXPORT_TEST,
                ClasspathAssembler.Input.export(project, locked));

        // printed so the gate log shows all five lists as the class produced them
        System.out.println("COMPILE     " + compile);
        System.out.println("TEST        " + test);
        System.out.println("RUN         " + run);
        System.out.println("EXPORT_MAIN " + exportMain);
        System.out.println("EXPORT_TEST " + exportTest);

        String classes = dir.resolve("target/classes").toString();
        String testClasses = dir.resolve("target/test-classes").toString();

        assertEquals(1, compile.size(), "strict visibility: the MAIN jar only");
        assertTrue(compile.get(0).endsWith(".jar"));

        assertEquals(List.of(testClasses, classes), test.subList(0, 2));
        assertEquals(5, test.size(),
                "test classes + main classes + two non-processor jars + the running jeno location");
        assertTrue(test.get(2).endsWith(".jar"));
        assertEquals(classes, test.get(test.size() - 1),
                "runtimeLocation is target/classes in the development layout");

        assertEquals(classes, run.get(0));
        assertEquals(2, run.size(), "MAIN jar only, dev and processor skipped");

        assertEquals(classes, exportMain.get(0));
        assertEquals(2, exportMain.size());

        assertEquals(List.of(testClasses, classes), exportTest.subList(0, 2));
        assertEquals(4, exportTest.size(), "MAIN + DEV jars, processor excluded");

        // both test-shaped classpaths exclude the processor: it compiles code,
        // it is not linked against; jeno test puts it on -processorpath instead
        String procJar = jarOf("com.example:lib-proc");
        assertFalse(test.contains(procJar));
        assertFalse(exportTest.contains(procJar));
    }

    @Test
    void apiRowsJoinTheCompileAndIdeClasspaths() throws Exception {
        LockedArtifact donor = locked.stream()
                .filter(a -> a.id().equals("com.example:lib-dev"))
                .findFirst().orElseThrow();
        LockedArtifact api = new LockedArtifact("com.example:lib-api", donor.version(),
                donor.source(), donor.url(), donor.sha256(), donor.metaSha256(),
                1, com.jujin.jeno.resolve.Scope.MAIN, true);
        List<LockedArtifact> withApi = new java.util.ArrayList<>(locked);
        withApi.add(api);

        List<String> compile = strings(ClasspathAssembler.Mode.COMPILE,
                ClasspathAssembler.Input.compile(project, withApi));
        List<String> exportMain = strings(ClasspathAssembler.Mode.EXPORT_MAIN,
                ClasspathAssembler.Input.export(project, withApi));

        assertEquals(2, compile.size(), "the direct MAIN jar plus the API-marked jar");
        assertEquals(2, exportMain.size() - 1,
                "main classes plus the direct and API-marked jars");
        assertTrue(compile.stream().anyMatch(path -> path.equals(store.findByDigest(
                        donor.sha256(), ArtifactStore.Kind.JAR).toString())),
                "the API row resolves through the same content-addressed artifact: " + compile);
    }

    @Test
    void aStaleLockIsNamedByTheAssembler() throws Exception {
        var error = assertThrows(ClasspathAssembler.ClasspathException.class,
                () -> ClasspathAssembler.of(ClasspathAssembler.Mode.COMPILE, dir, store,
                        ClasspathAssembler.Input.compile(project, List.of())));

        assertTrue(error.getMessage().contains("jeno.lock is stale"), error.getMessage());
        assertTrue(error.getMessage().contains("com.example:lib-main"), error.getMessage());
    }

    private List<String> strings(ClasspathAssembler.Mode mode,
                                 ClasspathAssembler.Input input) {
        return ClasspathAssembler.of(mode, dir, store, input).entries().stream()
                .map(Path::toString).toList();
    }

    private String jarOf(String id) throws IOException {
        for (LockedArtifact artifact : locked) {
            if (artifact.id().equals(id)) {
                return store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR).toString();
            }
        }
        throw new AssertionError("no such artifact " + id);
    }

    private void writeArtifact(String group, String name, String version, String deps)
            throws IOException {
        Path pom = dir.resolve("vendor/" + group.replace('.', '/') + "/" + name + "/" + version
                + "/" + name + "-" + version + ".pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              %s
            </project>
            """.formatted(group, name, version, deps));
        Path jar = pom.resolveSibling(name + "-" + version + ".jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n".getBytes());
            out.closeEntry();
        }
        if (name.equals("lib-proc")) {
            Files.writeString(dir.resolve("vendor/" + group.replace('.', '/') + "/" + name + "/"
                    + version + "/" + name + "-" + version + ".pom"),
                    Files.readString(pom));
        }
    }
}
