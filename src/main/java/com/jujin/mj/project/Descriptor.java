package com.jujin.mj.project;

import java.util.List;
import java.util.Map;

/**
 * A mj-native release descriptor ({@code {artifact}-{version}.mj.toml}) —
 * SPEC.md §3's closed schema as data. The descriptor travels next to the jar
 * and carries only facts: its own dependencies, its java requirement, and
 * which of its earlier versions the author retracted. Rules (budget, repos,
 * scripts) live only in the consumer's mj.toml.
 *
 * @param name      must equal the artifact name the consumer asked for
 * @param version   must equal the version the consumer asked for
 * @param javaVersion nullable — unset means no requirement
 */
public record Descriptor(
        String name,
        String version,
        Integer javaVersion,
        List<Requirement> requirements,
        Map<String, String> retract) {

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
}
