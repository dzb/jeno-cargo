package com.jujin.mj.project;

import java.util.List;
import java.util.Map;

/**
 * A mj-native release descriptor ({@code {artifact}-{version}.mj.toml}) —
 * SPEC.md §3's closed schema as data. The descriptor travels next to the jar
 * and carries only facts: its own dependencies, its java requirement, which
 * of its earlier versions the author retracted, and the identity facts a
 * human needs to recognize the library ({@code summary}, {@code homepage},
 * {@code license}).
 *
 * <p>The identity facts are facts, not rules: they are immutable once
 * published, never take part in resolution, and are read only by
 * {@code mj info}. Rules (budget, repos, scripts) still live only in the
 * consumer's mj.toml.
 *
 * @param name      must equal the artifact name the consumer asked for
 * @param version   must equal the version the consumer asked for
 * @param javaVersion nullable — unset means no requirement
 * @param group     nullable — the reverse-DNS group, so a consumer can address
 *                  the artifact as {@code group:artifact}; absent in
 *                  descriptors published before the field existed
 * @param summary   nullable — one line: what the library is for
 * @param homepage  nullable — a browsable project page (https)
 * @param license   nullable — an SPDX identifier (Apache-2.0, MIT, …)
 */
public record Descriptor(
        String name,
        String group,
        String version,
        Integer javaVersion,
        List<Requirement> requirements,
        Map<String, String> retract,
        String summary,
        String homepage,
        String license) {

    public Descriptor {
        if (requirements == null) {
            requirements = List.of();
        }
        requirements = List.copyOf(requirements);
        if (retract == null) {
            retract = Map.of();
        }
        retract = Map.copyOf(retract);
    }

    /** Back-compat: a descriptor with no identity facts. */
    public Descriptor(String name, String version, Integer javaVersion,
                      List<Requirement> requirements, Map<String, String> retract) {
        this(name, null, version, javaVersion, requirements, retract, null, null, null);
    }
}
