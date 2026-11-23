package com.jujin.jeno.project;

/**
 * jeno.toml (or a descriptor) violated the file-format spec: an unknown section,
 * a wrong value type, a malformed coordinate, a rule that SPEC.md froze. The
 * message names the section and the rule; the file name is the caller's to
 * prepend.
 */
public final class ProjectException extends RuntimeException {

    public ProjectException(String message) {
        super(message);
    }
}
