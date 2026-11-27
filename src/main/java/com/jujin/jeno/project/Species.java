package com.jujin.jeno.project;

/**
 * The species of a dependency, decided by the value's shape in jeno.toml (or a
 * descriptor): a bare version means Maven Central, a {@code gh:} shorthand
 * means a GitHub release, a {@code https://…jar} URL means a bare leaf.
 *
 * <p>{@code gitee:} was removed on 2026-11-22: its release assets need a token
 * even for reading, which contradicts jeno's zero-configuration promise. A bare
 * URL or a jeno-native repository is the replacement.
 */
public enum Species {
    CENTRAL,
    GH,
    /** A native release served by a named jeno-native repository (name/version layout). */
    NAMED,
    URL
}
