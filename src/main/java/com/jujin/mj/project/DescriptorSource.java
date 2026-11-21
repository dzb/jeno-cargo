package com.jujin.mj.project;

/**
 * Supplies the raw release descriptor of mj-native artifacts — the mj-native
 * counterpart of {@link PomSource}. The descriptor travels next to the jar
 * under the same release URL, so the resolver hands over the URL it already
 * computed and the implementation fetches it (the store-backed one reads the
 * content-addressed store; a test passes a map).
 */
@FunctionalInterface
public interface DescriptorSource {

    /** The raw descriptor text at {@code url}; throws when it cannot be fetched. */
    String descriptor(String url);
}
