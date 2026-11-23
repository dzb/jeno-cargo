package com.jujin.jeno.test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes surefire-shaped XML: one {@code TEST-<class>.xml} per test class, so
 * GitHub's test reporter and the usual CI consumers read it without a plugin.
 *
 * <p>No {@code timestamp} attribute — two runs differ only in the {@code time}
 * values, which are the real durations CI dashboards read. Everything else is
 * stable (classes and methods sorted), so a diff shows what changed and how
 * long it took.
 */
final class TestReports {

    /** One executed (or skipped) test case, with its wall time in nanoseconds. */
    record Case(String className, String name, long durationNanos, Throwable failure,
                boolean skipped) {}

    private TestReports() {}

    /** Writes one file per class; returns how many it wrote. */
    static int write(Path directory, List<Case> cases) throws IOException {
        Files.createDirectories(directory);
        Map<String, List<Case>> byClass = new LinkedHashMap<>();
        for (Case testCase : cases) {
            byClass.computeIfAbsent(testCase.className(), ignored -> new ArrayList<>())
                    .add(testCase);
        }
        for (Map.Entry<String, List<Case>> entry : byClass.entrySet()) {
            Path file = directory.resolve("TEST-" + entry.getKey() + ".xml");
            Files.writeString(file, document(entry.getKey(), entry.getValue()),
                    StandardCharsets.UTF_8);
        }
        return byClass.size();
    }

    private static String document(String className, List<Case> cases) {
        int tests = cases.size();
        int failures = 0;
        int skipped = 0;
        long suiteNanos = 0;
        for (Case testCase : cases) {
            suiteNanos += testCase.durationNanos();
            if (testCase.skipped()) {
                skipped++;
            } else if (testCase.failure() != null) {
                failures++;
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<testsuite name=\"").append(Xml.attribute(className))
                .append("\" tests=\"").append(tests)
                .append("\" failures=\"").append(failures)
                .append("\" errors=\"0\" skipped=\"").append(skipped)
                .append("\" time=\"").append(seconds(suiteNanos)).append("\">\n");
        for (Case testCase : cases) {
            out.append("  <testcase name=\"").append(Xml.attribute(testCase.name()))
                    .append("\" classname=\"").append(Xml.attribute(className))
                    .append("\" time=\"").append(seconds(testCase.durationNanos())).append("\"");
            if (testCase.skipped()) {
                out.append(">\n    <skipped/>\n  </testcase>\n");
            } else if (testCase.failure() != null) {
                out.append(">\n    <failure message=\"")
                        .append(Xml.attribute(String.valueOf(testCase.failure())))
                        .append("\" type=\"")
                        .append(Xml.attribute(testCase.failure().getClass().getName()))
                        .append("\">").append(Xml.text(stack(testCase.failure())))
                        .append("</failure>\n  </testcase>\n");
            } else {
                out.append("/>\n");
            }
        }
        out.append("</testsuite>\n");
        return out.toString();
    }

    /** Seconds with three decimals, the JUnit convention. */
    private static String seconds(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000_000.0);
    }

    /** The full stack, the way a person would print it — CI reads this part. */
    static String stack(Throwable failure) {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            failure.printStackTrace(writer);
        }
        return text.toString();
    }
}
