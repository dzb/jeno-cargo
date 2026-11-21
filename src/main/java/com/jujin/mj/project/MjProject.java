package com.jujin.mj.project;

import java.util.List;
import java.util.Map;

/**
 * A parsed mj.toml — SPEC.md §2's closed schema as data. Values that were
 * validated: coordinate shapes, semver tags, https URLs, the depth budget.
 * Everything downstream (resolver, lockfile, build) consumes this record and
 * never re-reads the file.
 *
 * @param javaVersion     the {@code [java].version} major, or null when unset
 * @param maxDepth        the dependency-chain budget, 1–255, default 2
 * @param repos           named repositories (name → https base URL), plus nothing
 *                        for Central — Central is always the implicit default
 * @param devRequirements {@code [dev-deps]} — test-only requirements; they
 *                        resolve into mj.lock but never reach the compile
 *                        visibility, the runtime classpath or a published
 *                        descriptor (Cargo's dev-dependencies)
 */
public record MjProject(
        String name,
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
        String license) {

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
    }
}
