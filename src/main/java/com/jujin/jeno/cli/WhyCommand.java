package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.jeno.pom.PomSource;
import com.jujin.jeno.project.DescriptorSource;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.resolve.Resolver;
import com.jujin.jeno.resolve.ResolvedGraph;
import com.jujin.jeno.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jeno why &lt;id&gt;}: every introduction path of one artifact — the
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
    public Command.Category category() {
        return Command.Category.INSPECT;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.OPTIONAL)
                .writes(CommandCapabilities.WriteScope.CACHE)
                .workspace(CommandCapabilities.WorkspaceMode.MODULE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        ProjectFiles.rejectLegacy(projectDir);
        boolean json = args.contains("--json");
        if (json && Workspaces.rootAt(projectDir).isPresent() && !hasModuleOption(args)) {
            JsonResult.error("why", 2, "MODULE_REQUIRED",
                    "this directory is a workspace root — say which module: --module <path>")
                    .emit(2);
            return 2;
        }
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new WhyCommand(store, pomSource, descriptorSource, dir)
                        .execute(rest));
        if (workspace != null) {
            return workspace;
        }

        String target = null;
        for (String arg : args) {
            if (arg.equals("--json")) {
                continue;
            }
            if (target != null) {
                String message = "why takes exactly one id — got '" + arg + "'";
                if (json) {
                    JsonResult.error("why", 2, "USAGE", message).emit(2);
                } else {
                    System.err.println(Ui.errPrefix() + message);
                }
                return 2;
            }
            target = arg;
        }
        if (target == null) {
            String message = "why needs an id — jeno why <group:artifact> (or a name)";
            if (json) {
                JsonResult.error("why", 2, "USAGE", message).emit(2);
            } else {
                System.err.println(Ui.errPrefix() + message);
            }
            return 2;
        }

        final String wanted = target;
        JenoProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("jeno.toml")));
        } catch (IOException e) {
            String message = "no jeno.toml in " + projectDir + " — run jeno init to create one";
            if (json) {
                JsonResult.error("why", 1, "MANIFEST_MISSING", message).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + message);
            }
            return 1;
        }
        ResolvedGraph graph;
        try {
            graph = LockedGraphView.graph(projectDir, project, pomSource, descriptorSource);
        } catch (LockedGraphView.OutOfDateException e) {
            if (json) {
                JsonResult.error("why", 1, "LOCK_STALE", e.getMessage()).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + e.getMessage());
            }
            return 1;
        }
        Substitutions substitutions = Substitutions.forProject(projectDir);

        ResolvedGraph.ResolvedArtifact artifact = graph.artifacts().stream()
                .filter(a -> a.id().equals(wanted))
                .findFirst()
                .orElse(null);
        if (artifact == null) {
            if (json) {
                JsonResult result = JsonResult.start("why")
                        .workspace(projectDir, "single")
                        .put("found", false)
                        .put("target", target);
                result.emit(0);
                return 0;
            }
            System.out.println("'" + target + "' is not in the graph — it is not declared "
                    + "and no dependency pulls it");
            return 0;
        }

        List<TracedPath> paths = paths(graph, target);
        if (json) {
            JsonObject identity = JsonUtils.object();
            identity.put("id", artifact.id());
            identity.put("version", artifact.version());
            identity.put("depth", artifact.depth());
            identity.put("declared", artifact.declared());
            identity.put("scope", artifact.scope().name()
                    .toLowerCase(java.util.Locale.ROOT));
            identity.put("api", artifact.api());
            identity.put("marker", substitutions.marker(artifact.id()));
            JsonArray pathsJson = JsonUtils.array();
            JsonArray pathEdgesJson = JsonUtils.array();
            for (TracedPath path : paths) {
                JsonArray one = JsonUtils.array();
                path.nodes().forEach(one::add);
                pathsJson.add(one);
                JsonArray edges = JsonUtils.array();
                for (int i = 1; i < path.nodes().size(); i++) {
                    JsonObject edge = JsonUtils.object();
                    edge.put("from", path.nodes().get(i - 1));
                    edge.put("to", path.nodes().get(i));
                    edge.put("api", path.apiEdges().get(i - 1));
                    edges.add(edge);
                }
                pathEdgesJson.add(edges);
            }
            JsonResult.start("why")
                    .workspace(projectDir, "single")
                    .put("found", true)
                    .put("artifact", identity)
                    .put("paths", pathsJson)
                    .put("pathEdges", pathEdgesJson)
                    .put("introductionPaths", paths.size())
                    .emit(0);
            return 0;
        }

        System.out.println(target + "@" + artifact.version() + " — depth " + artifact.depth()
                + (artifact.declared() ? ", declared directly" : "")
                + (artifact.api() ? ", api" : "")
                + substitutions.marker(artifact.id()));
        if (paths.isEmpty()) {
            System.out.println("  (declared: the root of its own path)");
            return 0;
        }
        for (TracedPath path : paths) {
            StringBuilder line = new StringBuilder("  via ");
            for (int i = 0; i < path.nodes().size(); i++) {
                if (i > 0) {
                    line.append(path.apiEdges().get(i - 1) ? " --api-> " : " -> ");
                }
                line.append(path.nodes().get(i));
            }
            System.out.println(line);
        }
        if (paths.size() > 1) {
            System.out.println(paths.size() + " introduction paths; the depth budget counts "
                    + "the longest");
        }
        return 0;
    }

    /** One path plus whether each transition is an explicit API edge. */
    private record TracedPath(List<String> nodes, List<Boolean> apiEdges) {}

    /** Every simple path from a declared root to {@code target}. */
    private static List<TracedPath> paths(ResolvedGraph graph, String target) {
        List<TracedPath> found = new ArrayList<>();
        for (ResolvedGraph.ResolvedArtifact root : graph.direct()) {
            List<String> rootPath = List.of(display(root));
            if (root.id().equals(target)) {
                found.add(new TracedPath(rootPath, List.of()));
                continue;
            }
            collect(graph, root.id(), target, graph.edges(), rootPath,
                    new ArrayList<>(), found, 32);
        }
        return found;
    }

    private static void collect(ResolvedGraph graph, String current, String target,
                                Map<String, List<String>> edges, List<String> path,
                                List<Boolean> apiEdges, List<TracedPath> found, int budget) {
        if (budget <= 0) {
            return;  // defensive: a graph that deep has already failed the budget
        }
        for (String child : edges.getOrDefault(current, List.of())) {
            List<String> next = new ArrayList<>(path);
            next.add(display(graph, child));
            List<Boolean> nextApiEdges = new ArrayList<>(apiEdges);
            nextApiEdges.add(graph.apiEdges().getOrDefault(current, Set.of()).contains(child));
            if (child.equals(target)) {
                found.add(new TracedPath(List.copyOf(next), List.copyOf(nextApiEdges)));
                continue;
            }
            if (path.contains(child)) {
                continue;  // a cycle cannot be on a simple path to the target
            }
            collect(graph, child, target, edges, next, nextApiEdges, found, budget - 1);
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
    private static boolean hasModuleOption(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("--module") || arg.startsWith("--module="));
    }

}
