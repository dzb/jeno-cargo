package com.jujin.mj.project;

import com.jujin.freeway.commons.toml.TomlUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a mj-native release descriptor ({@code {artifact}-{version}.mj.toml})
 * into {@link Descriptor} under SPEC.md §3's closed schema. The descriptor is
 * facts only: dependencies in the same value shapes as mj.toml, an optional
 * java requirement, an optional {@code [retract]} table. Everything that is a
 * rule — repositories, limits, overrides, scripts — is rejected here, because
 * the rules live only in the consumer's mj.toml.
 *
 * <p>Consistency is part of the schema: the descriptor's name and version
 * must equal what the consumer asked for (they also appear in the file name;
 * the URL check happens where the URL is built).
 */
public final class DescriptorReader {

    private DescriptorReader() {}

    /**
     * Parses one descriptor. {@code expectedName} / {@code expectedVersion}
     * are the artifact the consumer is resolving — a descriptor that declares
     * different ones is a mismatched or mislabeled file.
     */
    public static Descriptor parse(String text, String expectedName, String expectedVersion) {
        Map<String, Object> root = TomlUtils.parse(text);

        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                throw new ProjectException("descriptor " + expectedName + ": " + entry.getKey()
                        + " must be a table — every descriptor entry is a [section] (SPEC.md §3)");
            }
            switch (entry.getKey()) {
                case "project", "java", "deps", "retract" -> { }
                default -> throw new ProjectException("descriptor " + expectedName
                        + ": unknown section [" + entry.getKey()
                        + "] — a descriptor carries facts only (SPEC.md §3)");
            }
        }

        Map<String, Object> projectSection = section(root, "project");
        String name = requireString(projectSection, "project", "name");
        String version = requireString(projectSection, "project", "version");
        if (!name.equals(expectedName)) {
            throw new ProjectException("descriptor declares name '" + name + "' but was fetched as '"
                    + expectedName + "'");
        }
        if (!version.equals(expectedVersion)) {
            throw new ProjectException("descriptor declares version '" + version
                    + "' but was fetched as '" + expectedVersion + "'");
        }

        Integer javaVersion = null;
        Map<String, Object> javaSection = optionalSection(root, "java");
        if (javaSection != null) {
            javaVersion = requireInteger(javaSection, "java", "version");
        }

        // identity facts — optional, immutable, read only by `mj info`
        String group = optionalString(projectSection, "project", "group");
        String summary = optionalString(projectSection, "project", "summary");
        String homepage = optionalString(projectSection, "project", "homepage");
        String license = optionalString(projectSection, "project", "license");
        if (homepage != null && !homepage.startsWith("https://")) {
            throw new ProjectException("descriptor [project].homepage must be an https URL, got "
                    + homepage);
        }

        List<Requirement> requirements = List.of();
        Map<String, Object> depsSection = optionalSection(root, "deps");
        if (depsSection != null) {
            requirements = ProjectReader.extractDependencies(depsSection, null, "descriptor");
        }

        Map<String, String> retract = new LinkedHashMap<>();
        Map<String, Object> retractSection = optionalSection(root, "retract");
        if (retractSection != null) {
            for (Map.Entry<String, Object> entry : retractSection.entrySet()) {
                if (entry.getKey().equals(expectedVersion)) {
                    throw new ProjectException("descriptor " + expectedName
                            + " retracts its own version " + expectedVersion
                            + " — a release never retracts itself (SPEC.md §3.4)");
                }
                retract.put(entry.getKey(), requireString(retractSection, "retract", entry.getKey()));
            }
        }

        return new Descriptor(name, group, version, javaVersion, requirements, retract,
                summary, homepage, license);
    }

    private static String optionalString(Map<String, Object> section, String sectionName, String key) {
        Object value = section.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string) || string.isBlank()) {
            throw new ProjectException("descriptor [" + sectionName + "]." + key
                    + " must be a non-blank string when present");
        }
        return string;
    }

    private static Map<String, Object> section(Map<String, Object> root, String name) {
        Object value = root.get(name);
        if (!(value instanceof Map)) {
            throw new ProjectException("descriptor " + name + " section is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) value;
        return section;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalSection(Map<String, Object> root, String name) {
        Object value = root.get(name);
        return value == null ? null : (Map<String, Object>) value;
    }

    private static String requireString(Map<String, Object> section, String sectionName, String key) {
        Object value = section.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new ProjectException("descriptor [" + sectionName + "]." + key
                    + " must be a non-blank string");
        }
        return string;
    }

    private static Integer requireInteger(Map<String, Object> section, String sectionName, String key) {
        Object value = section.get(key);
        if (!(value instanceof Long integer)) {
            throw new ProjectException("descriptor [" + sectionName + "]." + key
                    + " must be an integer");
        }
        return integer.intValue();
    }
}
