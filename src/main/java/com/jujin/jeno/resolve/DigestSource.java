package com.jujin.jeno.resolve;

/**
 * Computes the sha256 of a download URL's content — the seam that lets the
 * resolver write lockfiles without downloading anything (tests pin the
 * format with a stub; the M3 download client computes from bytes).
 */
@FunctionalInterface
public interface DigestSource {

    /** The sha256 of the content at {@code url}, as 64 lowercase hex characters. */
    String sha256(String url);
}
