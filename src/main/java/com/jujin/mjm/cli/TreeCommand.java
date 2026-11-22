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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mjm tree}: the resolved graph from the declared roots down, each node
 * with its depth — the visible shape of the dependency budget (DESIGN.md
 * §4.2). Diagnosing re-resolves (idempotent; the lockfile stays the build's
 * input).
 *
 * <p>Two blocks: the declarations first, one line per root and no markers, so
 * every root is listed exactly once and never looks like a repeated node; then
 * one subtree per root, drawn with {@code ├─}/{@code └─} and indentation. A
 * node met again inside a subtree prints {@code (*) above} instead of a second
 * copy, and its subtree is not walked twice.
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

        List<Requirement> requirements = new ArrayList<>(project.requirements());
        requirements.addAll(project.devRequirements());

        // The declaration block: every root once, in declaration order, with
        // no repeated-node marker — a root is not "above" anything.
        Set<String> printed = new HashSet<>();
        List<ResolvedGraph.ResolvedArtifact> roots = new ArrayList<>();
        for (Requirement requirement : requirements) {
            ResolvedGraph.ResolvedArtifact root = byId.get(requirement.id());
            if (printed.add(root.id())) {
                System.out.println(line(root, ""));
                roots.add(root);
            }
        }

        // The subtrees: one per root, prefix grown by "├─ "/"└─ " and the
        // continuation columns. `printed` already holds the roots, so a child
        // that is also a root prints the marker and is not expanded here.
        Set<String> expanded = new HashSet<>();
        for (ResolvedGraph.ResolvedArtifact root : roots) {
            if (!expanded.contains(root.id())) {
                expand(root, "", graph, printed, expanded);
            }
        }
        return 0;
    }

    private void expand(ResolvedGraph.ResolvedArtifact artifact, String prefix,
                        ResolvedGraph graph, Set<String> printed, Set<String> expanded) {
        expanded.add(artifact.id());
        List<String> children = graph.edges().getOrDefault(artifact.id(), List.of());
        for (int i = 0; i < children.size(); i++) {
            boolean last = i == children.size() - 1;
            ResolvedGraph.ResolvedArtifact child = byId(graph, children.get(i));
            boolean repeat = !printed.add(child.id());
            System.out.println(prefix + (last ? "└─ " : "├─ ")
                    + line(child, repeat ? "  (*) above" : ""));
            if (repeat) {
                continue;  // the subtree is printed above; do not walk it twice
            }
            expand(child, prefix + (last ? "   " : "│  "), graph, printed, expanded);
        }
    }

    private static String line(ResolvedGraph.ResolvedArtifact artifact, String suffix) {
        return artifact.id() + "@" + artifact.version()
                + "  [depth " + artifact.depth()
                + (artifact.declared() ? ", declared" : "")
                + (artifact.scope() == com.jujin.mjm.resolve.Scope.DEV ? ", dev" : "")
                + "]" + suffix;
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
