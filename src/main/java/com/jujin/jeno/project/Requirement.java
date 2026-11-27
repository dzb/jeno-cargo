package com.jujin.jeno.project;

/**
 * One dependency declaration: a requirement the resolver must satisfy. The
 * fields that matter depend on the species —
 *
 * <ul>
 *   <li>{@link Species#CENTRAL}: {@code group} + {@code artifactId} +
 *       {@code version}, looked up in {@code repository} (the named repo, or
 *       Maven Central when null)</li>
 *   <li>{@link Species#GH}: {@code owner} + {@code repo} + {@code tag} +
 *       {@code version} (the tag without its {@code v})</li>
 *   <li>{@link Species#NAMED}: {@code version} plus either a manifest-local
 *       {@code repository} alias or a canonical {@code repositoryUrl} — a
 *       native release in a named jeno-native repository, whose layout has no
 *       group and no tag: the artifact name is the id</li>
 *   <li>{@link Species#URL}: {@code url} only — a bare leaf, no metadata</li>
 * </ul>
 *
 * <p>{@code id} is the graph identity: {@code group:artifact} for Central
 * ({@code group:artifact:classifier} when a classifier is present, so a base
 * jar and its {@code -sources}/{@code -native} sibling never collide), the
 * declared key for named jeno, GitHub native, and URL requirements.
 *
 * @param repositoryUrl canonical jeno repository declaration for a
 *                      descriptor-absolute native requirement; null when only a
 *                      manifest alias is present
 * @param classifier null for a plain jar; e.g. {@code linux-x86_64},
 *                   {@code sources}. It is part of the artifact's identity and
 *                   of its download URL ({@code artifact-version-classifier.jar})
 */
public record Requirement(
        String id,
        Species species,
        String group,
        String artifactId,
        String version,
        String repository,
        String repositoryUrl,
        String url,
        String owner,
        String repo,
        String tag,
        String classifier) {

    /** Back-compat: no canonical repository URL and no classifier. */
    public Requirement(String id, Species species, String group, String artifactId,
                       String version, String repository, String url, String owner,
                       String repo, String tag) {
        this(id, species, group, artifactId, version, repository, null,
                url, owner, repo, tag, null);
    }

    /** Back-compat: no canonical repository URL. */
    public Requirement(String id, Species species, String group, String artifactId,
                       String version, String repository, String url, String owner,
                       String repo, String tag, String classifier) {
        this(id, species, group, artifactId, version, repository, null,
                url, owner, repo, tag, classifier);
    }

    /** The GitHub origin, {@code gh:owner/repo}. */
    public String origin() {
        return species == Species.GH ? "gh:" + owner + "/" + repo : null;
    }

    /**
     * The canonical source identity used by the resolver to reject aliases.
     * Two requirements with the same id but different identities are a hard
     * conflict; two different ids with the same identity are also rejected.
     * The exact spelling is internal but stable enough for error messages.
     */
    public String identity() {
        return switch (species) {
            case CENTRAL -> repository == null
                    ? "central:" + id
                    : "repo:" + repository + "/" + id;
            case GH -> "gh:" + owner + "/" + repo;
            case NAMED -> {
                String source = repositoryUrl != null ? repositoryUrl : repository;
                if (source == null) {
                    throw new IllegalStateException(
                            "native requirement '" + id + "' has no repository source");
                }
                yield "named:" + (source.endsWith("/") ? source : source + "/") + id;
            }
            case URL -> "url:" + url;
        };
    }
}
