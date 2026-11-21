package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.pom.PomSource;
import com.jujin.mjm.project.DescriptorSource;
import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.project.Requirement;
import com.jujin.mjm.resolve.Resolver;
import com.jujin.mjm.resolve.ResolvedGraph;
import com.jujin.mjm.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mjm tree}: the resolved graph from the declared roots down, each node
 * with its depth — the visible shape of the dependency budget (DESIGN.md
 * §4.2). Diagnosing re-resolves (idempotent; the lockfile stays the build's
 * input). A node shown twice prints a marker instead of its subtree.
 */
public final class TreeCommand implements Command {

    private final ArtifactStore store;
    private final PomSource pomSource;
    private final DescriptorSource descriptorSource;
    private final Path projectDir;

    @Inject
    public TreeCommand(ArtifactStore store, PomSource pomSource,
                       DescriptorSource descriptorSource, Path projectDir) {
        this.store = store;
        this.pomSource = pomSource;
        this.descriptorSource = descriptorSource;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "tree";
    }

    @Override
    public String summary() {
        return "print the resolved graph with depths";
    }

    @Override
    public int execute(List<String> args) {
        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
        } catch (IOException e) {
            System.err.println("mjm: no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        }
        ResolvedGraph graph = Resolver.resolve(project, pomSource, descriptorSource);

        Map<String, ResolvedGraph.ResolvedArtifact> byId = new java.util.LinkedHashMap<>();
        for (ResolvedGraph.ResolvedArtifact artifact : graph.artifacts()) {
            byId.put(artifact.id(), artifact);
        }

        System.out.println(project.name() + "@" + project.version()
                + " (budget " + project.maxDepth() + ")");
        Set<String> shown = new HashSet<>();
        for (Requirement requirement : project.requirements()) {
            render(byId.get(requirement.id()), graph, "", true, shown, project);
        }
        for (Requirement requirement : project.devRequirements()) {
            render(byId.get(requirement.id()), graph, "", true, shown, project);
        }
        return 0;
    }

    private void render(ResolvedGraph.ResolvedArtifact artifact, ResolvedGraph graph,
                        String prefix, boolean last, Set<String> shown, MjProject project) {
        String connector = prefix.isEmpty() ? "" : (last ? "└─ " : "├─ ");
        String marker = shown.contains(artifact.id()) ? "  (*) above" : "";
        System.out.println(prefix + connector + artifact.id() + "@" + artifact.version()
                + "  [depth " + artifact.depth()
                + (artifact.declared() ? ", declared" : "")
                + (artifact.scope() == com.jujin.mjm.resolve.Scope.DEV ? ", dev" : "")
                + "]" + marker);
        boolean fresh = shown.add(artifact.id());
        if (!fresh) {
            return;  // the subtree is printed above; do not walk it twice
        }
        List<String> children = graph.edges().getOrDefault(artifact.id(), List.of());
        for (int i = 0; i < children.size(); i++) {
            boolean childLast = i == children.size() - 1;
            String childPrefix = prefix.isEmpty()
                    ? ""
                    : prefix + (last ? "   " : "│  ");
            render(byId(graph, children.get(i)), graph, childPrefix, childLast, shown, project);
        }
    }

    private static ResolvedGraph.ResolvedArtifact byId(ResolvedGraph graph, String id) {
        for (ResolvedGraph.ResolvedArtifact artifact : graph.artifacts()) {
            if (artifact.id().equals(id)) {
                return artifact;
            }
        }
        throw new IllegalStateException("graph edge to unknown artifact " + id);
    }
}
