package com.jujin.mj.project;

/**
 * One dependency declaration: a requirement the resolver must satisfy. The
 * fields that matter depend on the species —
 *
 * <ul>
 *   <li>{@link Species#CENTRAL}: {@code group} + {@code artifactId} +
 *       {@code version}, looked up in {@code repository} (the named repo, or
 *       Maven Central when null)</li>
 *   <li>{@link Species#GH} / {@link Species#GITEE}: {@code owner} + {@code repo}
 *       + {@code tag} + {@code version} (the tag without its {@code v})</li>
 *   <li>{@link Species#URL}: {@code url} only — a bare leaf, no metadata</li>
 * </ul>
 *
 * <p>{@code id} is the graph identity: {@code group:artifact} for Central,
 * the repo name for mj-native, the declared name for URLs.
 */
public record Requirement(
        String id,
        Species species,
        String group,
        String artifactId,
        String version,
        String repository,
        String url,
        String owner,
        String repo,
        String tag) {

    /** The mj-native origin, {@code gh:owner/repo} or {@code gitee:owner/repo}. */
    public String origin() {
        return switch (species) {
            case GH -> "gh:" + owner + "/" + repo;
            case GITEE -> "gitee:" + owner + "/" + repo;
            default -> null;
        };
    }
}
