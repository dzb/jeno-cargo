package com.jujin.mjm.store;

import com.jujin.mjm.project.DescriptorSource;

/**
 * The mj-native metadata source: descriptors come from the store, which
 * downloads on demand and pins their sha256.
 */
public final class StoreDescriptorSource implements DescriptorSource {

    private final ArtifactStore store;

    public StoreDescriptorSource(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public String descriptor(String url) {
        return store.readText(url, ArtifactStore.Kind.DESCRIPTOR);
    }
}
