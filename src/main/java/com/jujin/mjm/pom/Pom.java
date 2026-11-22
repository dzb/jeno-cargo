package com.jujin.mjm.pom;

import java.util.List;

/**
 * A Maven {@code pom.xml} decoded into mjm's own model — the wire format never
 * leaks past this type (DESIGN.md D3: XML is Central's dialect; mjm listens,
 * it does not speak).
 *
 * <p>The record holds the <em>effective</em> view: coordinates and packaging
 * are inherited from the parent chain and interpolated, {@code dependencies}
 * lists exactly the entries another project inherits from this one —
 * compile/runtime scope, non-optional, with versions resolved through
 * interpolation and {@code dependencyManagement} (including BOM imports).
 * Test/provided/system scopes never propagate transitively, so they are not
 * here.
 *
 * @param packaging  {@code jar} (bundles included) or {@code pom} (BOMs and
 *                   other metadata-only artifacts)
 */
public record Pom(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        List<PomDependency> dependencies) {

    public Pom {
        if (dependencies == null) {
            dependencies = List.of();
        }
        dependencies = List.copyOf(dependencies);
    }

    /** The Maven coordinate, {@code group:artifact:version}. */
    public String coordinate() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
