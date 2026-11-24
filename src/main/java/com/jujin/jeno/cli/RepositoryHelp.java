package com.jujin.jeno.cli;

/**
 * Shared CLI help for repository configuration that is too security-sensitive
 * to leave implicit or duplicate between publish and release.
 */
final class RepositoryHelp {

    private RepositoryHelp() {}

    static final String INSECURE_TOKEN = """
        insecure = true is a per-repository write opt-in, valid only when
        type = "jeno" and url is a literal private-network http:// address.
        It permits JENO_PUBLISH_TOKEN on that cleartext destination only.
        It does not turn HTTP into HTTPS, alter artifact hashes, lock sources,
        or anonymous readback, and it is not stored in jeno.lock.
        Prefer HTTPS. Target the repository by name (jeno publish mine /
        jeno release mine) so this policy is applied. JENO_ALLOW_INSECURE_TOKEN=1
        remains a process-wide escape hatch, not a per-repository policy.""";
}
