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
 * <p>One root per line, each followed by its own subtree — the classic shape,
 * so a second-level node can never read as the next root. Direct children carry
 * {@code ├─}/{@code └─}, deeper nodes grow the continuation columns, and a node
 * already printed above prints {@code (*) above} instead of its subtree again.
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
    public String usage() {
        return "[--json]";
    }

    @Override
    public String help() {
        return """
               each artifact prints once, at its first occurrence: a declared
               dependency that first appears inside another subtree gets no root
               line of its own — its 'declared' marker still shows on that line""";
    }

    @Override
    public int execute(List<String> args) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new TreeCommand(store, pomSource, descriptorSource, dir)
                        .execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean json = false;
        for (String arg : args) {
            if (arg.equals("--json")) {
                json = true;
            } else {
                System.err.println("mjm: tree takes no arguments except --json");
                return 2;
            }
        }
        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
        } catch (IOException e) {
            System.err.println("mjm: no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        }
        ResolvedGraph graph;
        try {
            graph = LockedGraphView.graph(projectDir, project, pomSource, descriptorSource);
        } catch (LockedGraphView.OutOfDateException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }
        Substitutions substitutions = Substitutions.forProject(projectDir);

        Map<String, ResolvedGraph.ResolvedArtifact> byId = new java.util.LinkedHashMap<>();
        for (ResolvedGraph.ResolvedArtifact artifact : graph.artifacts()) {
            byId.put(artifact.id(), artifact);
        }

        if (json) {
            printJson(project, graph);
            return 0;
        }

        System.out.println(project.name() + "@" + project.version()
                + " (budget " + project.maxDepth() + ")");

        List<Requirement> requirements = new ArrayList<>(project.requirements());
        requirements.addAll(project.devRequirements());

        // Classic tree: each root's line, then its own subtree immediately, so
        // a child can never be mistaken for the next root. `printed` holds every
        // node already shown; meeting one again prints the marker and stops.
        Set<String> printed = new HashSet<>();
        for (Requirement requirement : requirements) {
            ResolvedGraph.ResolvedArtifact root = byId.get(requirement.id());
            if (!printed.add(root.id())) {
                continue;  // the same root reached twice is still one line
            }
            System.out.println(line(root, "", substitutions));
            expand(root, "", graph, printed, substitutions);
        }
        return 0;
    }

    private void expand(ResolvedGraph.ResolvedArtifact artifact, String prefix,
                        ResolvedGraph graph, Set<String> printed,
                        Substitutions substitutions) {
        List<String> children = graph.edges().getOrDefault(artifact.id(), List.of());
        for (int i = 0; i < children.size(); i++) {
            boolean last = i == children.size() - 1;
            ResolvedGraph.ResolvedArtifact child = byId(graph, children.get(i));
            boolean repeat = !printed.add(child.id());
            System.out.println(prefix + (last ? "└─ " : "├─ ")
                    + line(child, repeat ? "  (*) above" : "", substitutions));
            if (!repeat) {
                expand(child, prefix + (last ? "   " : "│  "), graph, printed,
                        substitutions);
            }
        }
    }

    /**
     * One JSON document, nothing else: the artifact list plus each artifact's
     * children. No nested tree — a consumer walks {@code children}, so the same
     * fact is never represented twice.
     */
    private static void printJson(MjProject project, ResolvedGraph graph) {
        var root = com.jujin.freeway.commons.json.JsonUtils.object();
        var identity = com.jujin.freeway.commons.json.JsonUtils.object();
        identity.put("name", project.name());
        identity.put("version", project.version());
        root.put("project", identity);
        root.put("budget", project.maxDepth());

        var roots = com.jujin.freeway.commons.json.JsonUtils.array();
        java.util.List<Requirement> requirements = new ArrayList<>(project.requirements());
        requirements.addAll(project.devRequirements());
        for (Requirement requirement : requirements) {
            roots.add(requirement.id());
        }
        root.put("roots", roots);

        var artifacts = com.jujin.freeway.commons.json.JsonUtils.array();
        graph.artifacts().stream()
                .sorted(java.util.Comparator.comparing(ResolvedGraph.ResolvedArtifact::id))
                .forEach(artifact -> {
                    var entry = com.jujin.freeway.commons.json.JsonUtils.object();
                    entry.put("id", artifact.id());
                    entry.put("version", artifact.version());
                    entry.put("depth", artifact.depth());
                    entry.put("declared", artifact.declared());
                    entry.put("dev", artifact.scope()
                            == com.jujin.mjm.resolve.Scope.DEV);
                    var children = com.jujin.freeway.commons.json.JsonUtils.array();
                    graph.edges().getOrDefault(artifact.id(), List.of()).stream()
                            .sorted()
                            .forEach(children::add);
                    entry.put("children", children);
                    artifacts.add(entry);
                });
        root.put("artifacts", artifacts);

        System.out.println(root);
    }

    private static String line(ResolvedGraph.ResolvedArtifact artifact, String suffix,
                               Substitutions substitutions) {
        return artifact.id() + "@" + artifact.version()
                + substitutions.marker(artifact.id())
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
