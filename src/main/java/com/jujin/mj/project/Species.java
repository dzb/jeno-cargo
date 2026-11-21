package com.jujin.mj.project;

/**
 * The species of a dependency, decided by the value's shape in mj.toml (or a
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
