package com.jujin.jeno.resolve;

import com.jujin.jeno.pom.Pom;
import com.jujin.jeno.pom.PomDependency;
import com.jujin.jeno.pom.PomException;
import com.jujin.jeno.pom.PomSource;
import com.jujin.jeno.pom.Poms;
import com.jujin.jeno.project.Descriptor;
import com.jujin.jeno.project.DescriptorReader;
import com.jujin.jeno.project.DescriptorSource;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectException;
import com.jujin.jeno.project.Repository;
import com.jujin.jeno.project.Requirement;
import com.jujin.jeno.project.Species;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The dependency-graph resolver — jeno's three-piece soul in one pass:
 *
 * <ol>
 *   <li><b>Constraint collection</b> — every requirement (declared or
 *       transitive) is gathered with the path that introduced it. Exclusions
 *       are per-path, Maven-style: the same library may arrive under
 *       different exclusion sets, and each set expands its own subtree, so a
 *       subtree removed under one path still enters through another.</li>
 *   <li><b>Semantic-aware MVS</b> — per id, same major takes the max
 *       (Maven ordering); a cross-major demand is a hard conflict with both
 *       paths named. Overrides win first, and retracted mj-native versions
 *       drop out unless the consumer pinned them directly.</li>
 *   <li><b>Depth budget over the longest path</b> — a node's depth is the
 *       longest chain that reaches it (the honest reading of "how deep is
 *       this library"); cycles are errors, and any node past
 *       {@code max-depth} fails with its full introduction path and the
 *       exits that stay inside the rules.</li>
 * </ol>
 *
 * <p>Strict visibility costs nothing here: the graph keeps the declared
 * artifacts and the closure together, and the build (M3) compiles against
 * the first and runs against the second (DESIGN.md §4.4).
 */
public final class Resolver {

    /** Maven Central's canonical repository base. */
    private static final String CENTRAL_BASE = "https://repo.maven.apache.org/maven2/";

    /**
     * Expansion budget for the collection phase. Exclusion sets are
     * per-path, so pathological graphs can fork combinatorially; a real
     * project never gets near this, and crossing it is a diagnosis, not a
     * hang.
     */
    private static final int MAX_EXPANSIONS = 5_000;

    private Resolver() {}

    public static ResolvedGraph resolve(
            JenoProject project, PomSource pomSource, DescriptorSource descriptorSource) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(pomSource, "pomSource");
        Objects.requireNonNull(descriptorSource, "descriptorSource");
        Session session = new Session(project, pomSource, descriptorSource);
        session.collect();
        Map<String, String> selected = session.select();
        return session.graph(selected);
    }

    // ------------------------------------------------------------------
    // one pass of collection, selection and budgeting
    // ------------------------------------------------------------------

    private static final class Session {

        private final JenoProject project;
        private final PomSource pomSource;
        private final DescriptorSource descriptorSource;

        /** id → (version → metadata); fetched at most once per version. */
        private final Map<String, Map<String, Metadata>> metadataById = new LinkedHashMap<>();
        /** id → species and origin, frozen at the first reference. */
        private final Map<String, Species> speciesById = new LinkedHashMap<>();
        private final Map<String, String> originById = new LinkedHashMap<>();
        /** id → the requirement shape metadata is fetched with (g/a or owner/repo). */
        private final Map<String, Requirement> templateById = new LinkedHashMap<>();
        private final Map<String, List<Constraint>> constraintsById = new LinkedHashMap<>();
        /**
         * The edges the constraints actually walked: parent id → parent
         * version → child ids. Rebuilding edges from metadata would resurrect
         * subtrees a path exclusion removed, so the graph is what the
         * collection phase walked, nothing else.
         */
        private final Map<String, Map<String, Set<String>>> edgesFrom = new LinkedHashMap<>();
        private int expansions;

        Session(JenoProject project, PomSource pomSource, DescriptorSource descriptorSource) {
            this.project = project;
            this.pomSource = pomSource;
            this.descriptorSource = descriptorSource;
        }

        // ----------------------------------------------------------------
        // phase 1 — constraints
        // ----------------------------------------------------------------

        void collect() {
            Deque<Constraint> queue = new ArrayDeque<>();
            // MAIN first: a dev declaration that meets a main artifact one
            // scope later must not flip it to DEV (MAIN wins, Cargo's rule).
            for (Requirement requirement : project.requirements()) {
                if (project.overrides().containsKey(requirement.id())) {
                    throw new ResolveException("'" + requirement.id()
                            + "' is declared in [deps] and pinned in [overrides] — change the "
                            + "declaration or drop the override, never both");
                }
                queue.add(constraint(requirement.id(), requirement.version(),
                        List.of(display(requirement.id(), requirement.version())),
                        true, Set.of(), requirement));
            }
            for (Requirement requirement : project.devRequirements()) {
                if (project.overrides().containsKey(requirement.id())) {
                    throw new ResolveException("'" + requirement.id()
                            + "' is declared in [dev-deps] and pinned in [overrides] — change the "
                            + "declaration or drop the override, never both");
                }
                queue.add(constraint(requirement.id(), requirement.version(),
                        List.of(display(requirement.id(), requirement.version())),
                        true, Set.of(), requirement));
            }
            // annotation processors: their own reach, resolved like any other
            // coordinate so the lockfile pins and verifies them
            for (Requirement requirement : project.processorRequirements()) {
                queue.add(constraint(requirement.id(), requirement.version(),
                        List.of(display(requirement.id(), requirement.version())),
                        false, Set.of(), requirement));
            }
            while (!queue.isEmpty()) {
                Constraint current = queue.poll();
                if (!expand(current)) {
                    continue;  // this version's metadata already expanded under this exclusion set
                }
                Metadata metadata = metadata(current.id(), current.version());
                for (Edge edge : metadata.requirements()) {
                    if (excluded(current.exclusions(), edge.requirement())) {
                        continue;  // an ancestor's exclusion removes this subtree
                    }
                    edgesFrom.computeIfAbsent(current.id(), ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(current.version(), ignored -> new LinkedHashSet<>())
                            .add(edge.requirement().id());
                    Set<PomDependency.Exclusion> inherited = new LinkedHashSet<>(current.exclusions());
                    inherited.addAll(edge.exclusions());
                    queue.add(constraint(edge.requirement().id(), edge.requirement().version(),
                            append(current.path(), display(edge.requirement().id(),
                                    edge.requirement().version())),
                            false, Set.copyOf(inherited), edge.requirement()));
                }
            }
        }

        /**
         * Expands one constraint's subtree unless the same version already
         * expanded under the same exclusion set. Central transitives inherit
         * the parent's repository — a corp-hosted library's dependencies live
         * where the library lives.
         */
        private boolean expand(Constraint constraint) {
            if (expansions >= MAX_EXPANSIONS) {
                throw new ResolveException("the dependency graph expanded past " + MAX_EXPANSIONS
                        + " distinct paths — the exclusion sets are forking combinatorially; "
                        + "simplify the exclusions in play");
            }
            String key = constraint.id() + "@" + constraint.version() + "@"
                    + constraint.exclusions().hashCode();
            Map<String, Metadata> byVersion =
                    metadataById.computeIfAbsent(constraint.id(), ignored -> new LinkedHashMap<>());
            if (byVersion.containsKey(key)) {
                return false;
            }
            expansions++;
            // Metadata is cached per version; the expansion marker is per
            // (version, exclusion set) — they are different facts.
            if (!byVersion.containsKey(constraint.version())) {
                byVersion.put(constraint.version(), loadMetadata(constraint.id(),
                        constraint.version(), constraint.template()));
            }
            byVersion.put(key, byVersion.get(constraint.version()));
            return true;
        }

        private Constraint constraint(
                String id, String version, List<String> path, boolean declared,
                Set<PomDependency.Exclusion> exclusions, Requirement template) {

            Species species = template.species();
            Species known = speciesById.get(id);
            if (known == null) {
                speciesById.put(id, species);
                originById.put(id, template.origin());
                templateById.put(id, template);
            } else {
                String knownOrigin = originById.get(id);
                if (known != species || !Objects.equals(knownOrigin, template.origin())) {
                    throw new ResolveException("'" + id + "' is referenced as " + knownOrigin
                            + " and " + template.origin() + " — one id, one species");
                }
            }
            Constraint constraint = new Constraint(id, version, path, declared, exclusions, template);
            constraintsById.computeIfAbsent(id, key -> new ArrayList<>()).add(constraint);
            return constraint;
        }

        private Metadata metadata(String id, String version) {
            Map<String, Metadata> byVersion = metadataById.get(id);
            Metadata metadata = byVersion == null ? null : byVersion.get(version);
            if (metadata != null) {
                return metadata;
            }
            metadata = loadMetadata(id, version, templateById.get(id));
            metadataById.computeIfAbsent(id, key -> new LinkedHashMap<>()).put(version, metadata);
            return metadata;
        }

        private Metadata loadMetadata(String id, String version, Requirement template) {
            String repositoryBase = template.repository() == null
                    ? ArtifactLocations.centralBase()
                    : project.repos().get(template.repository()).declaration();
            try {
                return switch (template.species()) {
                    case CENTRAL -> {
                        Pom pom = Poms.decode(template.group(), template.artifactId(),
                                version, repositoryBase, pomSource);
                        List<Edge> edges = pom.dependencies().stream()
                                .map(dependency -> new Edge(
                                        centralRequirement(dependency, template.repository()),
                                        Set.copyOf(dependency.exclusions())))
                                .toList();
                        yield new Metadata(edges, Map.of());
                    }
                    case GH -> {
                        // the same entry point jarUrl/metadataUrl use: a mirror
                        // set through -Djeno.github.host must cover the descriptor
                        // fetch too, or it is not a mirror
                        String url = ArtifactLocations.releaseFile(
                                ArtifactLocations.githubHost(), template.owner(),
                                template.repo(), template.tag(), version, "jeno.toml");
                        String text = descriptorSource.descriptor(url);
                        if (text == null || text.isBlank()) {
                            throw new ProjectException("no descriptor for "
                                    + template.owner() + "/" + template.repo() + "@" + version
                                    + " was found on the source");
                        }
                        Descriptor descriptor = DescriptorReader.parse(text,
                                template.repo(), version);
                        List<Edge> edges = descriptor.requirements().stream()
                                .map(requirement -> new Edge(requirement,
                                        Set.<PomDependency.Exclusion>of()))
                                .toList();
                        yield new Metadata(edges, descriptor.retract());
                    }
                    case NAMED -> {
                        String base = template.repository() == null
                                ? ArtifactLocations.centralBase()
                                : project.repos().get(template.repository()).declaration();
                        String url = ArtifactLocations.jenoFile(base, template.id(), version,
                                "jeno.toml");
                        String text = descriptorSource.descriptor(url);
                        if (text == null || text.isBlank()) {
                            throw new ProjectException("no descriptor at " + url);
                        }
                        Descriptor descriptor = DescriptorReader.parse(text, template.id(), version,
                                template.repository());
                        List<Edge> edges = descriptor.requirements().stream()
                                .map(requirement -> new Edge(requirement,
                                        Set.<PomDependency.Exclusion>of()))
                                .toList();
                        yield new Metadata(edges, descriptor.retract());
                    }
                    case URL -> new Metadata(List.of(), Map.of());  // a bare leaf
                };
            } catch (PomException | ProjectException e) {
                throw new ResolveException(e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new ResolveException("unable to read the metadata of " + id + "@" + version, e);
            }
        }

        /**
         * One Central requirement from a decoded pom dependency. A classifier
         * is part of the identity ({@code group:artifact:classifier}): the base
         * jar and its {@code -native}/{@code -sources} siblings are different
         * artifacts with different URLs, and collapsing them onto one id would
         * make the graph pick one at random.
         */
        private static Requirement centralRequirement(PomDependency dependency,
                                                      String repository) {
            String classifier = dependency.classifier();
            boolean plain = classifier == null || classifier.isBlank();
            String id = plain
                    ? dependency.key()
                    : dependency.key() + ":" + classifier;
            return new Requirement(id, Species.CENTRAL, dependency.groupId(),
                    dependency.artifactId(), dependency.version(), repository,
                    null, null, null, null, plain ? null : classifier);
        }

        /** Whether any inherited exclusion removes this target from the subtree. */
        private static boolean excluded(Set<PomDependency.Exclusion> exclusions, Requirement target) {
            if (exclusions.isEmpty() || target.species() != Species.CENTRAL) {
                return false;
            }
            return exclusions.stream().anyMatch(exclusion ->
                    (exclusion.groupId().equals("*") || exclusion.groupId().equals(target.group()))
                            && (exclusion.artifactId().equals("*")
                                    || exclusion.artifactId().equals(target.artifactId())));
        }

        /** Transitive Central requirements inherit the parent's repository. */
        private static Requirement inherit(Requirement requirement, Constraint parent) {
            if (requirement.species() != Species.CENTRAL || requirement.repository() != null) {
                return requirement;
            }
            return new Requirement(requirement.id(), requirement.species(),
                    requirement.group(), requirement.artifactId(), requirement.version(),
                    parent.template().repository(), null, null, null, null,
                    requirement.classifier());
        }

        // ----------------------------------------------------------------
        // phase 2 — semantic-aware MVS
        // ----------------------------------------------------------------

        Map<String, String> select() {
            Map<String, String> selected = new LinkedHashMap<>();
            for (Map.Entry<String, List<Constraint>> entry : constraintsById.entrySet()) {
                String id = entry.getKey();
                List<Constraint> constraints = entry.getValue();
                String forced = project.overrides().get(id);
                if (forced != null) {
                    requireShape(id, forced, speciesById.get(id));
                    selected.put(id, forced);
                    metadata(id, forced);  // a pinned version no path referenced still needs its facts
                    continue;
                }

                List<String> candidates = constraints.stream()
                        .map(Constraint::version)
                        .distinct()
                        .toList();

                // Retracted mj-native versions drop out — unless the consumer
                // pinned one directly (explicit choice beats author retraction).
                Set<String> retracted = new HashSet<>();
                Set<String> declaredVersions = new HashSet<>();
                for (Constraint constraint : constraints) {
                    retracted.addAll(metadata(id, constraint.version()).retract().keySet());
                    if (constraint.declared()) {
                        declaredVersions.add(constraint.version());
                    }
                }
                List<String> surviving = candidates.stream()
                        .filter(version -> !retracted.contains(version)
                                || declaredVersions.contains(version))
                        .toList();
                if (surviving.isEmpty()) {
                    throw new ResolveException("'" + id + "' has no surviving version — every "
                            + "candidate (" + String.join(", ", candidates) + ") is retracted by its author");
                }
                selected.put(id, maximum(id, surviving, constraints));
            }
            return selected;
        }

        /**
         * Same major takes the max; a cross-major demand is a hard conflict —
         * silent max-wins across a major is exactly the NoSuchMethodError
         * trap jeno exists to close. Central versions without an extractable
         * major fall back to plain max-wins.
         */
        private String maximum(String id, List<String> candidates, List<Constraint> constraints) {
            List<Integer> majors = candidates.stream().map(Versions::majorOf).toList();
            if (majors.stream().allMatch(Objects::nonNull)) {
                Set<Integer> distinct = new HashSet<>(majors);
                if (distinct.size() > 1) {
                    StringBuilder conflict = new StringBuilder();
                    for (String candidate : candidates) {
                        Constraint via = constraints.stream()
                                .filter(constraint -> constraint.version().equals(candidate))
                                .findFirst()
                                .orElse(constraints.getFirst());
                        conflict.append("\n  ").append(display(id, candidate))
                                .append(" — required via ").append(via.path());
                    }
                    throw new ResolveException("'" + id + "' is required across majors "
                            + distinct.stream().sorted().toList() + ":" + conflict
                            + "\nexits: align the versions of your dependencies, or pin one in [overrides]");
                }
            }
            String best = candidates.getFirst();
            for (String candidate : candidates) {
                if (Versions.compare(candidate, best) > 0) {
                    best = candidate;
                }
            }
            return best;
        }

        private void requireShape(String id, String version, Species species) {
            if ((species == Species.GH || species == Species.NAMED)
                    && !Versions.isNativeSemver(version)) {
                throw new ResolveException("[overrides]." + id + " = \"" + version
                        + "\" — an mj-native override must be semver (1.2.3, 1.2.3-rc1)");
            }
            if (species == Species.CENTRAL
                    && (version.contains("${") || version.startsWith("[") || version.startsWith("(")
                            || version.endsWith("-SNAPSHOT"))) {
                throw new ResolveException("[overrides]." + id + " = \"" + version
                        + "\" — overrides pin one exact released version");
            }
        }

        // ----------------------------------------------------------------
        // phase 3 — the closed graph, its depths, its budget
        // ----------------------------------------------------------------

        ResolvedGraph graph(Map<String, String> selected) {
            Map<String, List<String>> children = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : selected.entrySet()) {
                Map<String, Set<String>> byVersion = edgesFrom.get(entry.getKey());
                Set<String> targets = byVersion == null
                        ? Set.of()
                        : byVersion.getOrDefault(entry.getValue(), Set.of());
                children.put(entry.getKey(), List.copyOf(targets));
            }

            Set<String> declaredIds = new HashSet<>();
            for (Requirement requirement : project.requirements()) {
                declaredIds.add(requirement.id());
            }
            for (Requirement requirement : project.devRequirements()) {
                declaredIds.add(requirement.id());
            }

            // Scope: an artifact reachable from any MAIN root is MAIN; only
            // artifacts exclusively reachable from [dev-deps] are DEV. The
            // walk runs over the walked edges — the same graph the lockfile
            // records.
            Set<String> mainReachable = reachable(children, project.requirements());
            Set<String> processorReachable =
                    reachable(children, project.processorRequirements());


            Depths depths = new Depths(selected, children, declaredIds);
            depths.verifyAcyclic();
            for (String id : selected.keySet()) {
                depths.depth(id);
            }

            String offender = null;
            int offenderDepth = -1;
            for (String id : selected.keySet()) {
                int depth = depths.depth(id);
                if (depth > project.maxDepth() && depth > offenderDepth) {
                    offender = id;
                    offenderDepth = depth;
                }
            }
            if (offender != null) {
                throw new ResolveException("dependency " + display(offender, selected.get(offender))
                        + " enters the graph at depth " + offenderDepth
                        + " (budget is " + project.maxDepth() + "):\n  "
                        + String.join(" -> ", depths.longestPath(offender))
                        + "\nexits: swap it for a smaller (zero-dependency) library, or raise "
                        + "[limits].max-depth in jeno.toml");
            }

            List<ResolvedGraph.ResolvedArtifact> artifacts = new ArrayList<>();
            for (String id : new TreeMap<>(selected).keySet()) {
                String version = selected.get(id);
                Requirement template = templateById.get(id);
                Species species = speciesById.get(id);
                String source = switch (species) {
                    case CENTRAL -> template.repository() == null
                            ? "central"
                            : project.repos().get(template.repository()).declaration();
                    case GH -> originById.get(id);
                    case NAMED -> project.repos().get(template.repository()).declaration();
                    case URL -> "url";
                };
                artifacts.add(new ResolvedGraph.ResolvedArtifact(id, version, species, source,
                        jarUrl(template, version), metadataUrl(template, version),
                        depths.depth(id), declaredIds.contains(id),
                        mainReachable.contains(id) ? Scope.MAIN
                                : processorReachable.contains(id) ? Scope.PROCESSOR
                                : Scope.DEV));
            }
            List<ResolvedGraph.ResolvedArtifact> direct = artifacts.stream()
                    .filter(ResolvedGraph.ResolvedArtifact::declared)
                    .toList();
            return new ResolvedGraph(List.copyOf(artifacts), direct,
                    Map.copyOf(children));
        }

        /** Everything reachable from a set of roots over the walked edges. */
        private static Set<String> reachable(Map<String, List<String>> children,
                                             List<Requirement> roots) {
            Set<String> reached = new HashSet<>();
            Deque<String> frontier = new ArrayDeque<>();
            for (Requirement requirement : roots) {
                if (reached.add(requirement.id())) {
                    frontier.add(requirement.id());
                }
            }
            while (!frontier.isEmpty()) {
                String current = frontier.poll();
                for (String child : children.getOrDefault(current, List.of())) {
                    if (reached.add(child)) {
                        frontier.add(child);
                    }
                }
            }
            return reached;
        }

        /** The repository a requirement names, or null for Central's default. */
        private Repository repository(Requirement template) {
            return template.repository() == null
                    ? null
                    : project.repos().get(template.repository());
        }

        /** The base a POM fetch uses: the https base, or the directory itself. */
        private String fetchBase(String repositoryName) {
            return repositoryName == null
                    ? ArtifactLocations.centralBase()
                    : ArtifactLocations.fetchBase(project.repos().get(repositoryName));
        }

        private String jarUrl(Requirement template, String version) {
            Repository named = repository(template);
            return switch (template.species()) {
                case CENTRAL -> named == null
                        ? ArtifactLocations.mavenFile(ArtifactLocations.centralBase(),
                                template.group(), template.artifactId(), version,
                                template.classifier(), "jar")
                        : ArtifactLocations.mavenFile(named, template.group(),
                                template.artifactId(), version, template.classifier(), "jar");
                case GH -> ArtifactLocations.releaseFile(ArtifactLocations.githubHost(),
                        template.owner(), template.repo(), template.tag(), version, "jar");
                case NAMED -> ArtifactLocations.jenoFile(named, template.id(), version, "jar");
                case URL -> template.url();
            };
        }

        private String metadataUrl(Requirement template, String version) {
            Repository named = repository(template);
            return switch (template.species()) {
                case CENTRAL -> named == null
                        ? ArtifactLocations.mavenFile(ArtifactLocations.centralBase(),
                                template.group(), template.artifactId(), version, "pom")
                        : ArtifactLocations.mavenFile(named, template.group(),
                                template.artifactId(), version, "pom");
                case GH -> ArtifactLocations.releaseFile(ArtifactLocations.githubHost(),
                        template.owner(), template.repo(), template.tag(), version, "jeno.toml");
                case NAMED -> ArtifactLocations.jenoFile(named, template.id(), version, "jeno.toml");
                case URL -> null;  // a bare leaf has no metadata
            };
        }

        // ----------------------------------------------------------------
        // shared bits
        // ----------------------------------------------------------------

        private static String display(String id, String version) {
            return id + "@" + version;
        }

        private static List<String> append(List<String> path, String step) {
            List<String> appended = new ArrayList<>(path);
            appended.add(step);
            return List.copyOf(appended);
        }

        private record Edge(Requirement requirement, Set<PomDependency.Exclusion> exclusions) {

            Edge {
                if (exclusions == null) {
                    exclusions = Set.of();
                }
                exclusions = Set.copyOf(exclusions);
            }
        }

        private record Metadata(List<Edge> requirements, Map<String, String> retract) {

            Metadata {
                if (requirements == null) {
                    requirements = List.of();
                }
                requirements = List.copyOf(requirements);
                if (retract == null) {
                    retract = Map.of();
                }
                retract = Map.copyOf(retract);
            }
        }

        private record Constraint(
                String id,
                String version,
                List<String> path,
                boolean declared,
                Set<PomDependency.Exclusion> exclusions,
                Requirement template) {

            Constraint {
                if (exclusions == null) {
                    exclusions = Set.of();
                }
                exclusions = Set.copyOf(exclusions);
                if (path == null) {
                    path = List.of();
                }
                path = List.copyOf(path);
            }
        }

        /**
         * Longest-path depths with an explicit cycle sweep: a node's depth is
         * the longest chain from the declared roots — the honest measure of
         * "how deep is this library" — and a cycle makes that measure
         * undefined, so a cycle is an error, not a loop to survive.
         */
        private static final class Depths {

            private final Map<String, String> selected;
            private final Map<String, List<String>> children;
            private final Set<String> declaredIds;
            private final Map<String, List<String>> parents = new LinkedHashMap<>();
            private final Map<String, Integer> depths = new LinkedHashMap<>();

            Depths(Map<String, String> selected, Map<String, List<String>> children,
                   Set<String> declaredIds) {
                this.selected = selected;
                this.children = children;
                this.declaredIds = declaredIds;
                for (Map.Entry<String, List<String>> entry : children.entrySet()) {
                    for (String target : entry.getValue()) {
                        parents.computeIfAbsent(target, key -> new ArrayList<>())
                                .add(entry.getKey());
                    }
                }
            }

            void verifyAcyclic() {
                Map<String, Integer> state = new LinkedHashMap<>();  // 1 on the stack, 2 done
                List<String> stack = new ArrayList<>();
                for (String id : selected.keySet()) {
                    visit(id, state, stack);
                }
            }

            private void visit(String id, Map<String, Integer> state, List<String> stack) {
                Integer mark = state.get(id);
                if (mark != null) {
                    if (mark == 1) {
                        List<String> cycle = new ArrayList<>(
                                stack.subList(stack.indexOf(id), stack.size()));
                        cycle.add(id);
                        throw new ResolveException("circular dependency: "
                                + String.join(" -> ", cycle.stream()
                                        .map(node -> display(node, selected.get(node)))
                                        .toList())
                                + "\nexits: break the cycle by swapping one of its members");
                    }
                    return;  // already expanded
                }
                state.put(id, 1);
                stack.add(id);
                for (String child : children.getOrDefault(id, List.of())) {
                    visit(child, state, stack);
                }
                stack.remove(stack.getLast());
                state.put(id, 2);
            }

            int depth(String id) {
                Integer memo = depths.get(id);
                if (memo != null) {
                    return memo;
                }
                int depth;
                if (declaredIds.contains(id)) {
                    depth = 1;  // a direct dependency sits at depth 1, always
                } else {
                    List<String> ownParents = parents.getOrDefault(id, List.of());
                    int deepest = 0;
                    for (String parent : ownParents) {
                        deepest = Math.max(deepest, depth(parent));
                    }
                    depth = ownParents.isEmpty() ? 1 : deepest + 1;
                }
                depths.put(id, depth);
                return depth;
            }

            /** One longest chain to {@code id}, from a depth-1 root down. */
            List<String> longestPath(String id) {
                List<String> path = new ArrayList<>();
                path.add(display(id, selected.get(id)));
                String current = id;
                while (!declaredIds.contains(current)
                        && !parents.getOrDefault(current, List.of()).isEmpty()) {
                    int target = depths.get(current) - 1;
                    String next = parents.get(current).stream()
                            .filter(parent -> depths.get(parent) == target)
                            .findFirst()
                            .orElse(null);
                    if (next == null) {
                        break;
                    }
                    path.add(display(next, selected.get(next)));
                    current = next;
                }
                return List.copyOf(path.reversed());
            }
        }
    }
}
