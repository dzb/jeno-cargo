package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.project.Species;
import com.jujin.mj.resolve.ArtifactLocations;
import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.LockedArtifact;
import com.jujin.mj.test.MjAfterEach;
import com.jujin.mj.test.MjBeforeEach;
import com.jujin.mj.test.MjTest;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.File;
import java.lang.annotation.Annotation;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code mj test} — two runners behind one command:
 *
 * <ol>
 *   <li><b>the built-in runner</b> (node:test's shape) for {@code @MjTest}
 *       classes — failures are any thrown exception</li>
 *   <li><b>the JUnit Platform</b> (Launcher + Jupiter engine) for existing
 *       {@code org.junit.jupiter.api.Test} classes — being able to run the
 *       tests a project already has is the point, so adopting mj never
 *       abandons test assets</li>
 * </ol>
 *
 * <p>The JUnit toolchain (jupiter api/engine + platform launcher, one frozen
 * version mj manages) is injected into the test classpath and downloaded
 * through the store like every other dependency — sha256 pinned, auditable.
 * When the closure already declares {@code org.junit.jupiter:junit-jupiter-api},
 * the closure's version wins and nothing is injected (one id, one version).
 */
public final class TestCommand implements Command {

    private static final String TEST_SOURCES = "src/test/java";
    private static final String TEST_OUTPUT = "target/test-classes";

    /** The JUnit toolchain mj manages: one frozen version, store-pinned. */
    private static final List<String[]> JUNIT_TOOLCHAIN = List.of(
            new String[]{"org.junit.jupiter", "junit-jupiter-api", "6.1.3"},
            new String[]{"org.junit.jupiter", "junit-jupiter-engine", "6.1.3"},
            new String[]{"org.junit.platform", "junit-platform-launcher", "6.1.3"});

    private final ArtifactStore store;
    private final Path projectDir;

    public TestCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String summary() {
        return "compile and run src/test/java — @MjTest built-in plus JUnit (Jupiter) tests";
    }

    @Override
    public int execute(List<String> args) {
        if (!Files.exists(projectDir.resolve("mj.lock"))) {
            System.err.println("mj: no mj.lock in " + projectDir + " — run mj lock first");
            return 1;
        }
        List<String> testSources = sources(projectDir.resolve(TEST_SOURCES));
        if (testSources.isEmpty()) {
            System.out.println("no tests under " + projectDir.resolve(TEST_SOURCES));
            return 0;
        }

        List<Path> toolchain = toolchainPaths();
        List<String> compileClasspath = classpath(toolchain, false);
        if (!compile(testSources, compileClasspath)) {
            return 1;
        }

        List<Class<?>> allClasses = discover(classpath(toolchain, true));
        List<Class<?>> mjClasses = new ArrayList<>();
        List<Class<?>> jupiterClasses = new ArrayList<>();
        for (Class<?> loaded : allClasses) {
            if (hasAnnotation(loaded, MjTest.class)) {
                mjClasses.add(loaded);
            } else if (hasAnnotation(loaded, org.junit.jupiter.api.Test.class)) {
                jupiterClasses.add(loaded);
            }
        }

        int passed = 0;
        int failures = 0;
        if (!mjClasses.isEmpty()) {
            int[] result = runMjTests(mjClasses);
            passed += result[0];
            failures += result[1];
        }
        if (!jupiterClasses.isEmpty()) {
            int[] result = runJupiterTests(jupiterClasses);
            passed += result[0];
            failures += result[1];
        }

        System.out.println("tests: " + (passed + failures) + ", passed: " + passed
                + ", failed: " + failures);
        return failures == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------
    // the built-in @MjTest runner
    // ------------------------------------------------------------------

    private int[] runMjTests(List<Class<?>> testClasses) {
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (Class<?> testClass : testClasses) {
            for (Method test : annotated(testClass, MjTest.class)) {
                String name = testClass.getName() + "." + test.getName();
                try {
                    // package-private test classes and methods are the norm;
                    // the runner is the privileged caller
                    test.setAccessible(true);
                    var constructor = testClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    Object instance = constructor.newInstance();
                    for (Method before : annotated(testClass, MjBeforeEach.class)) {
                        before.setAccessible(true);
                        before.invoke(instance);
                    }
                    test.invoke(instance);
                    for (Method after : annotated(testClass, MjAfterEach.class)) {
                        after.setAccessible(true);
                        after.invoke(instance);
                    }
                    passed++;
                    System.out.println("  ok  " + name);
                } catch (Throwable failure) {
                    failures.add(name);
                    Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                    System.out.println("  FAIL " + name + ": " + cause);
                }
            }
        }
        return new int[]{passed, failures.size()};
    }

    // ------------------------------------------------------------------
    // the JUnit Platform branch
    // ------------------------------------------------------------------

    private int[] runJupiterTests(List<Class<?>> testClasses) {
        List<DiscoverySelector> selectors = new ArrayList<>();
        for (Class<?> testClass : testClasses) {
            selectors.add(DiscoverySelectors.selectClass(testClass));
        }
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);

        var summary = listener.getSummary();
        summary.getFailures().forEach(failure -> System.out.println("  FAIL "
                + failure.getTestIdentifier().getDisplayName() + ": "
                + failure.getException()));
        System.out.println("  jupiter: " + summary.getTestsSucceededCount() + " passed, "
                + summary.getTestsFailedCount() + " failed");
        return new int[]{(int) summary.getTestsSucceededCount(),
                (int) summary.getTotalFailureCount()};
    }

    // ------------------------------------------------------------------
    // classpath: closure + target/classes + mj + the JUnit toolchain
    // ------------------------------------------------------------------

    private List<String> classpath(List<Path> toolchain, boolean includeTestOutput) {
        List<String> entries = new ArrayList<>();
        Path testOutput = projectDir.resolve(TEST_OUTPUT);
        if (includeTestOutput && Files.exists(testOutput)) {
            entries.add(testOutput.toString());
        }
        entries.add(projectDir.resolve("target/classes").toString());
        for (LockedArtifact artifact : lock()) {
            Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
            if (jar != null) {
                entries.add(jar.toString());
            }
        }
        entries.add(runtimeLocation().toString());  // the @MjTest API rides with the tool
        for (Path jar : toolchain) {
            entries.add(jar.toString());
        }
        return entries;
    }

    /**
     * The JUnit toolchain, from the distribution when mj runs from one
     * ({@code lib/toolchain/} next to the mj jar — shipped, offline, no
     * download) and from the store otherwise (the exploded development
     * layout). Injection applies only to the store path, and only when the
     * closure does not already declare junit-jupiter-api — one id, one
     * version (SPEC.md §4.2).
     */
    public List<Path> toolchainPaths() {
        Path shipped = shippedToolchain();
        if (shipped != null) {
            List<Path> jars = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(shipped)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".jar"))
                        .sorted()
                        .forEach(jars::add);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + shipped, e);
            }
            return List.copyOf(jars);
        }
        List<Path> paths = new ArrayList<>();
        boolean declaredInClosure = false;
        for (LockedArtifact artifact : lock()) {
            if (artifact.id().equals("org.junit.jupiter:junit-jupiter-api")) {
                declaredInClosure = true;
            }
        }
        if (declaredInClosure) {
            return paths;  // the closure owns the JUnit version
        }
        for (String[] coordinate : JUNIT_TOOLCHAIN) {
            String url = ArtifactLocations.mavenFile(ArtifactLocations.centralBase(),
                    coordinate[0], coordinate[1], coordinate[2], "jar");
            paths.add(store.ensure(url, ArtifactStore.Kind.JAR));
        }
        return paths;
    }

    /**
     * {@code lib/toolchain/} next to the mj runtime — present when mj runs
     * from a distribution ({@code lib/mj.jar}), absent in the exploded
     * development layout.
     */
    private Path shippedToolchain() {
        Path runtime = runtimeLocation();
        if (!runtime.toString().endsWith(".jar")) {
            return null;  // exploded development layout — no distribution lib/
        }
        Path toolchain = runtime.getParent().resolve("toolchain");
        return Files.isDirectory(toolchain) ? toolchain : null;
    }

    private List<LockedArtifact> lock() {
        try {
            return com.jujin.mj.store.LockFileReader.read(
                    Files.readString(projectDir.resolve("mj.lock")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mj.lock", e);
        }
    }

    private static Path runtimeLocation() {
        try {
            return Path.of(MjTest.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (Exception e) {
            return Path.of("");
        }
    }

    private boolean compile(List<String> sources, List<String> classpath) {
        Path output = projectDir.resolve(TEST_OUTPUT);
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            System.err.println("mj: cannot create " + output);
            return false;
        }
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        List<String> command = new ArrayList<>();
        command.add("-d");
        command.add(output.toString());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.addAll(sources);
        int exit = compiler.run(null, null, null, command.toArray(String[]::new));
        if (exit != 0) {
            System.err.println("mj: test compilation failed");
        }
        return exit == 0;
    }

    private List<Class<?>> discover(List<String> classpath) {
        List<URL> urls = new ArrayList<>();
        // the compile output is the discovery root — it did not exist when the
        // compile classpath was assembled, so it joins the loader here
        try {
            urls.add(projectDir.resolve(TEST_OUTPUT).toUri().toURL());
            for (String entry : classpath) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot assemble the test classpath", e);
        }
        // 项目类与测试类必须同一个加载器(package-private 才互通),且
        // 自身优先——否则父(工具 uber-jar)里的同名库会遮蔽被测类。
        // 平台与测试 API(org.junit.* / mj runtime / JDK)委托父,保持单份。
        ClassLoader loader = new java.net.URLClassLoader(
                urls.toArray(URL[]::new), getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (delegates(name)) {
                        return super.loadClass(name, resolve);
                    }
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }

            private boolean delegates(String name) {
                return name.startsWith("java.") || name.startsWith("javax.")
                        || name.startsWith("jdk.") || name.startsWith("sun.")
                        || name.startsWith("org.junit.") || name.startsWith("org.opentest4j.")
                        || name.startsWith("com.jujin.mj.")
                        || name.startsWith("com.jujin.freeway.ioc.");
            }
        };
        List<Class<?>> classes = new ArrayList<>();
        Path output = projectDir.resolve(TEST_OUTPUT);
        try (Stream<Path> stream = Files.walk(output)) {
            for (Path classFile : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .toList()) {
                String binary = output.relativize(classFile).toString()
                        .replace(File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                try {
                    classes.add(loader.loadClass(binary));
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // a companion class with unresolvable links — only classes
                    // carrying tests matter
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot scan " + output, e);
        }
        return classes;
    }

    private static boolean hasAnnotation(Class<?> type, Class<? extends Annotation> annotation) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static List<Method> annotated(Class<?> type,
                                          Class<? extends Annotation> annotation) {
        List<Method> found = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                found.add(method);
            }
        }
        return found;
    }

    private List<String> sources(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk " + root, e);
        }
    }
}
