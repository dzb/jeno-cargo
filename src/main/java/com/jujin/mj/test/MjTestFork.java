package com.jujin.mj.test;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
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
 * The test bootstrap that runs in a forked JVM — mj test forks a new JVM
 * with the test classpath and invokes this class. The forked JVM's classpath
 * IS the test classpath: test classes, code under test, closure jars, and
 * the junit toolchain. No mj process classes leak in, so there are no
 * classloader identity issues.
 *
 * <p>Supports two runners:
 * <ul>
 *   <li>{@code @MjTest} — built-in, zero-dep, failures are any thrown exception</li>
 *   <li>JUnit Jupiter ({@code org.junit.jupiter.api.Test}) — via the Platform Launcher</li>
 * </ul>
 *
 * <p>Usage: {@code java -cp <test-cp> com.jujin.mj.test.MjTestFork <test-classes-dir>}
 * Exit code: 0 = all passed, 1 = any failure.
 */
public final class MjTestFork {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: MjTestFork <test-classes-dir>");
            System.exit(2);
        }
        Path testClasses = Path.of(args[0]);

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
                    if (hasAnnotation(loaded, MjTest.class)) {
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

        // ---- @MjTest runner ----
        for (Class<?> testClass : mjTests) {
            for (Method test : annotated(testClass, MjTest.class)) {
                String name = testClass.getName() + "." + test.getName();
                try {
                    test.setAccessible(true);
                    var ctor = testClass.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance();
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
                    failed++;
                    Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
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
            LauncherFactory.create().execute(request, listener);

            var summary = listener.getSummary();
            summary.getFailures().forEach(failure ->
                    System.out.println("  FAIL " + failure.getTestIdentifier().getDisplayName()
                            + ": " + failure.getException()));
            passed += (int) summary.getTestsSucceededCount();
            failed += (int) summary.getTotalFailureCount();
        }

        System.out.println("tests: " + (passed + failed) + ", passed: " + passed
                + ", failed: " + failed);
        System.exit(failed > 0 ? 1 : 0);
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
        return found;
    }
}
