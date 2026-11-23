package com.jujin.jeno.test;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The test bootstrap that runs in a forked JVM — mjm test forks a new JVM
 * with the test classpath and invokes this class. The forked JVM's classpath
 * IS the test classpath: test classes, code under test, closure jars, and
 * the junit toolchain. No mjm process classes leak in, so there are no
 * classloader identity issues.
 *
 * <p>Supports two runners:
 * <ul>
 *   <li>{@code @JenoTest} — built-in, zero-dep, failures are any thrown exception</li>
 *   <li>JUnit Jupiter ({@code org.junit.jupiter.api.Test}) — via the Platform Launcher</li>
 * </ul>
 *
 * <p>Usage: {@code java -cp <test-cp> com.jujin.jeno.test.JenoTestFork <test-classes-dir>
 * [<results-dir>]}. With a results directory it also writes one surefire-shaped
 * {@code TEST-<class>.xml} per class.
 * Exit code: 0 = all passed, 1 = any failure.
 */
public final class JenoTestFork {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: MjmTestFork <test-classes-dir>");
            System.exit(2);
        }
        Path testClasses = Path.of(args[0]);
        Path resultsDir = args.length > 1 ? Path.of(args[1]) : null;

        List<Class<?>> mjTests = new ArrayList<>();
        List<Class<?>> jupiterTests = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(testClasses)) {
            for (Path classFile : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .toList()) {
                String binary = testClasses.relativize(classFile).toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                try {
                    Class<?> loaded = Class.forName(binary);
                    if (hasAnnotation(loaded, JenoTest.class)) {
                        mjTests.add(loaded);
                    } else if (hasAnnotation(loaded, org.junit.jupiter.api.Test.class)) {
                        jupiterTests.add(loaded);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // companion class or unresolvable — skip
                }
            }
        }

        int passed = 0;
        int failed = 0;
        List<TestReports.Case> cases = new ArrayList<>();

        // ---- @JenoTest runner ----
        for (Class<?> testClass : mjTests) {
            for (Method test : annotated(testClass, JenoTest.class)) {
                String name = testClass.getName() + "." + test.getName();
                long started = System.nanoTime();
                try {
                    test.setAccessible(true);
                    var ctor = testClass.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance();
                    for (Method before : annotated(testClass, JenoBeforeEach.class)) {
                        before.setAccessible(true);
                        before.invoke(instance);
                    }
                    test.invoke(instance);
                    for (Method after : annotated(testClass, JenoAfterEach.class)) {
                        after.setAccessible(true);
                        after.invoke(instance);
                    }
                    passed++;
                    cases.add(new TestReports.Case(testClass.getName(), test.getName(),
                            System.nanoTime() - started, null, false));
                    System.out.println("  ok  " + name);
                } catch (Throwable failure) {
                    failed++;
                    Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                    // a failing @JenoBeforeEach belongs to this case, not the suite
                    cases.add(new TestReports.Case(testClass.getName(), test.getName(),
                            System.nanoTime() - started, cause, false));
                    System.out.println("  FAIL " + name + ": " + cause);
                }
            }
        }

        // ---- JUnit Platform runner ----
        if (!jupiterTests.isEmpty()) {
            List<DiscoverySelector> selectors = new ArrayList<>();
            for (Class<?> testClass : jupiterTests) {
                selectors.add(DiscoverySelectors.selectClass(testClass));
            }
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectors)
                    .build();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            XmlListener reporter = new XmlListener(cases);
            LauncherFactory.create().execute(request, listener, reporter);

            var summary = listener.getSummary();
            summary.getFailures().forEach(failure ->
                    System.out.println("  FAIL " + failure.getTestIdentifier().getDisplayName()
                            + ": " + failure.getException()));
            passed += (int) summary.getTestsSucceededCount();
            failed += (int) summary.getTotalFailureCount();
        }

        if (resultsDir != null) {
            try {
                TestReports.write(resultsDir, cases);
            } catch (IOException e) {
                System.err.println("mjm: cannot write test reports to " + resultsDir
                        + " — " + e.getMessage());
            }
        }

        System.out.println("tests: " + (passed + failed) + ", passed: " + passed
                + ", failed: " + failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * Per-test results for the XML report. The summary listener only counts;
     * this one keeps one entry per test, with the throwable and skip state.
     */
    private static final class XmlListener implements TestExecutionListener {
        private final List<TestReports.Case> cases;
        private final java.util.Map<String, Long> started = new java.util.HashMap<>();

        XmlListener(List<TestReports.Case> cases) {
            this.cases = cases;
        }

        @Override
        public void executionStarted(TestIdentifier identifier) {
            if (identifier.isTest()) {
                started.put(identifier.getUniqueId(), System.nanoTime());
            }
        }

        @Override
        public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
            if (!identifier.isTest()) {
                return;
            }
            String className = className(identifier);
            long duration = durationOf(identifier);
            switch (result.getStatus()) {
                case SUCCESSFUL -> cases.add(new TestReports.Case(className,
                        identifier.getDisplayName(), duration, null, false));
                case FAILED -> cases.add(new TestReports.Case(className,
                        identifier.getDisplayName(), duration,
                        result.getThrowable().orElse(null), false));
                case ABORTED -> cases.add(new TestReports.Case(className,
                        identifier.getDisplayName(), duration, null, true));
            }
        }

        @Override
        public void executionSkipped(TestIdentifier identifier, String reason) {
            if (identifier.isTest()) {
                cases.add(new TestReports.Case(className(identifier),
                        identifier.getDisplayName(), durationOf(identifier), null, true));
            }
        }

        private long durationOf(TestIdentifier identifier) {
            Long start = started.remove(identifier.getUniqueId());
            return start == null ? 0L : System.nanoTime() - start;
        }

        private static String className(TestIdentifier identifier) {
            var source = identifier.getSource();
            if (source.isPresent()) {
                if (source.get() instanceof MethodSource method) {
                    return method.getClassName();
                }
                if (source.get() instanceof ClassSource type) {
                    return type.getClassName();
                }
            }
            return identifier.getDisplayName();
        }
    }

    private static boolean hasAnnotation(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotation) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static List<Method> annotated(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotation) {
        List<Method> found = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                found.add(method);
            }
        }
        // declared order is not specified; the report is diffed between runs
        found.sort(java.util.Comparator.comparing(Method::getName));
        return found;
    }
}
