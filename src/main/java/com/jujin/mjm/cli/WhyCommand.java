package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.pom.PomSource;
import com.jujin.mjm.project.DescriptorSource;
import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.resolve.Resolver;
import com.jujin.mjm.resolve.ResolvedGraph;
import com.jujin.mjm.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code mjm why &lt;id&gt;}: every introduction path of one artifact — the
 * "who pulled this in" answer that turns a depth-budget breach or a version
 * surprise into a readable decision (DESIGN.md §4.2's review moment).
 */
public final class WhyCommand implements Command {

    private final ArtifactStore store;
    private final PomSource pomSource;
    private final DescriptorSource descriptorSource;
    private final Path projectDir;

    @Inject
    public WhyCommand(ArtifactStore store, PomSource pomSource,
                      DescriptorSource descriptorSource, Path projectDir) {
        this.store = store;
        this.pomSource = pomSource;
        this.descriptorSource = descriptorSource;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "why";
    }

    @Override
    public String summary() {
        return "every path that introduces one artifact";
    }
    @Override
    public String usage() {
        return "<id>";
    }


    @Override
    public int execute(List<String> args) {
        if (args.isEmpty()) {
            System.err.println("mjm: why needs an id — mjm why <group:artifact> (or a name)");
            return 2;
        }
        String target = args.getFirst();
        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
        } catch (IOException e) {
            System.err.println("mjm: no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        }
        ResolvedGraph graph = Resolver.resolve(project, pomSource, descriptorSource);

        ResolvedGraph.ResolvedArtifact artifact = graph.artifacts().stream()
                .filter(a -> a.id().equals(target))
                .findFirst()
                .orElse(null);
        if (artifact == null) {
            System.out.println("'" + target + "' is not in the graph — it is not declared "
                    + "and no dependency pulls it");
            return 0;
        }

        System.out.println(target + "@" + artifact.version() + " — depth " + artifact.depth()
                + (artifact.declared() ? ", declared directly" : ""));
        List<List<String>> paths = paths(graph, target);
        if (paths.isEmpty()) {
            System.out.println("  (declared: the root of its own path)");
            return 0;
        }
        for (List<String> path : paths) {
            System.out.println("  via " + String.join(" -> ", path));
        }
        if (paths.size() > 1) {
            System.out.println(paths.size() + " introduction paths; the depth budget counts "
                    + "the longest");
        }
        return 0;
    }

    /** Every simple path from a declared root to {@code target}. */
    private static List<List<String>> paths(ResolvedGraph graph, String target) {
        List<List<String>> found = new ArrayList<>();
        for (ResolvedGraph.ResolvedArtifact root : graph.direct()) {
            List<String> rootPath = List.of(display(root));
            if (root.id().equals(target)) {
                found.add(rootPath);
                continue;
            }
            collect(graph, root.id(), target, graph.edges(), rootPath, found, 32);
        }
        return found;
    }

    private static void collect(ResolvedGraph graph, String current, String target,
                                Map<String, List<String>> edges, List<String> path,
                                List<List<String>> found, int budget) {
        if (budget <= 0) {
            return;  // defensive: a graph that deep has already failed the budget
        }
        for (String child : edges.getOrDefault(current, List.of())) {
            List<String> next = new ArrayList<>(path);
            next.add(display(graph, child));
            if (child.equals(target)) {
                found.add(List.copyOf(next));
                continue;
            }
            if (path.contains(child)) {
                continue;  // a cycle cannot be on a simple path to the target
            }
            collect(graph, child, target, edges, next, found, budget - 1);
        }
    }

    private static String display(ResolvedGraph.ResolvedArtifact artifact) {
        return artifact.id() + "@" + artifact.version();
    }

    private static String display(ResolvedGraph graph, String id) {
        return graph.artifacts().stream()
                .filter(artifact -> artifact.id().equals(id))
                .findFirst()
                .map(WhyCommand::display)
                .orElse(id);
    }
}
