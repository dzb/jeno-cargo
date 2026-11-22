package com.jujin.mjm.project;

import java.util.List;
import java.util.Map;

/**
 * A parsed mjm.toml — SPEC.md §2's closed schema as data. Values that were
 * validated: coordinate shapes, semver tags, https URLs, the depth budget.
 * Everything downstream (resolver, lockfile, build) consumes this record and
 * never re-reads the file.
 *
 * @param group           the {@code [project].group} — a reverse-DNS id
 *                        ({@code com.jujin8.mj}); null when unset. Required by
 *                        a Maven-layout publish (the path is group-based) and
 *                        carried into the published descriptor so consumers
 *                        address the library as {@code group:artifact}
 * @param javaVersion     the {@code [java].version} major, or null when unset
 * @param maxDepth        the dependency-chain budget, 1–255, default 2
 * @param repos           named repositories (name → https base URL), plus nothing
 *                        for Central — Central is always the implicit default
 * @param processors      {@code [build].processors} — annotation-processor
 *                        coordinates, resolved as their own closure and put on
 *                        javac's {@code -processorpath}; empty means javac's
 *                        default (the classpath)
 * @param devRequirements {@code [dev-deps]} — test-only requirements; they
 *                        resolve into mjm.lock but never reach the compile
 *                        visibility, the runtime classpath or a published
 *                        descriptor (Cargo's dev-dependencies)
 */
public record MjProject(
        String name,
        String group,
        String version,
        String main,
        Integer javaVersion,
        int maxDepth,
        List<Requirement> requirements,
        Map<String, String> overrides,
        Map<String, String> scripts,
        Map<String, String> repos,
        List<String> buildParameters,
        List<Requirement> devRequirements,
        String summary,
        String homepage,
        String license,
        List<String> processors,
        List<Requirement> processorRequirements,
        List<String> testInclude,
        List<String> testExclude) {

    public MjProject {
        if (requirements == null) {
            requirements = List.of();
        }
        requirements = List.copyOf(requirements);
        if (devRequirements == null) {
            devRequirements = List.of();
        }
        devRequirements = List.copyOf(devRequirements);
        if (overrides == null) {
            overrides = Map.of();
        }
        overrides = Map.copyOf(overrides);
        if (scripts == null) {
            scripts = Map.of();
        }
        scripts = Map.copyOf(scripts);
        if (repos == null) {
            repos = Map.of();
        }
        repos = Map.copyOf(repos);
        if (buildParameters == null) {
            buildParameters = List.of();
        }
        buildParameters = List.copyOf(buildParameters);
        if (processors == null) {
            processors = List.of();
        }
        processors = List.copyOf(processors);
        if (processorRequirements == null) {
            processorRequirements = List.of();
        }
        processorRequirements = List.copyOf(processorRequirements);
        if (testInclude == null) {
            testInclude = List.of();
        }
        testInclude = List.copyOf(testInclude);
        if (testExclude == null) {
            testExclude = List.of();
        }
        testExclude = List.copyOf(testExclude);
    }
}
