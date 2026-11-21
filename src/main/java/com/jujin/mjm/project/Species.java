package com.jujin.mjm.project;

/**
 * The species of a dependency, decided by the value's shape in mjm.toml (or a
 * descriptor): a bare version means Maven Central, a {@code gh:}/{@code gitee:}
 * shorthand means an mj-native release, a {@code https://…jar} URL means a
 * bare leaf.
 */
public enum Species {
    CENTRAL,
    GH,
    GITEE,
    URL
}
