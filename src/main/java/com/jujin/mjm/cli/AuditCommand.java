package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.mjm.project.Repository;
import com.jujin.mjm.resolve.Versions;
import com.jujin.mjm.store.LockFileReader;
import com.jujin.mjm.store.LockedArtifact;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mjm audit} — known vulnerabilities for the locked closure, from OSV.
 *
 * <p>Only artifacts addressed by Maven coordinates are checked: OSV's Maven
 * ecosystem is keyed by {@code group:artifact}, which a {@code gh:} release, an
 * {@code mjm:} repository, a local repository or a bare URL do not have. Those
 * are listed as <em>not checked</em> with the reason — never as clean, because
 * "nothing found" and "nothing could be asked" are different answers.
 *
 * <p>Exit code: 1 when anything is vulnerable or any query failed (a failed
 * query cannot be reported as clean), 0 when every checked artifact is clean.
 * {@code --offline} is refused: the whole answer lives on the network.
 */
public final class AuditCommand implements Command {

    private static final String OSV_DEFAULT = "https://api.osv.dev";

    // HTTP/1.1 on purpose, for the same reason the store is pinned there: a
    // single small request gains nothing from multiplexing, and HTTP/2 was the
    // transport that failed on the real network where HTTP/1.1 was stable.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final Path projectDir;

    public AuditCommand(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public String summary() {
        return "check locked dependencies for known vulnerabilities (OSV)";
    }

    @Override
    public String usage() {
        return "[--json] [--dev]";
    }

    @Override
    public boolean supportsOffline() {
        // every answer comes from an online database
        return false;
    }

    /** One advisory as the report needs it. */
    private record Advisory(String id, String severity, String summary, String fixed,
                            List<String> aliases) {}

    /** A vulnerable artifact and its advisories, in lock order. */
    private record Vulnerable(String id, String version, List<Advisory> advisories) {}

    private record NotChecked(String id, String reason) {}

    private record Failed(String id, String error) {}

    @Override
    public int execute(List<String> args) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new AuditCommand(dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean json = false;
        boolean includeDev = false;
        for (String arg : args) {
            switch (arg) {
                case "--json" -> json = true;
                case "--dev" -> includeDev = true;
                default -> {
                    System.err.println("mjm: audit takes --json and --dev only — got '" + arg + "'");
                    return 2;
                }
            }
        }

        List<LockedArtifact> locked;
        try {
            Path lock = projectDir.resolve("mjm.lock");
            if (!Files.exists(lock)) {
                System.err.println("mjm: no mjm.lock in " + projectDir + " — run mjm lock first");
                return 1;
            }
            locked = LockFileReader.read(Files.readString(lock));
        } catch (IOException e) {
            System.err.println("mjm: cannot read mjm.lock in " + projectDir);
            return 1;
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }

        List<LockedArtifact> scoped = new ArrayList<>();
        for (LockedArtifact artifact : locked) {
            if (includeDev || artifact.isMain()) {
                scoped.add(artifact);
            }
        }

        List<LockedArtifact> queryable = new ArrayList<>();
        List<NotChecked> notChecked = new ArrayList<>();
        for (LockedArtifact artifact : scoped) {
            if (isMavenCoordinate(artifact)) {
                queryable.add(artifact);
            } else {
                notChecked.add(new NotChecked(artifact.id(), reason(artifact.source())));
            }
        }

        List<Failed> failures = new ArrayList<>();
        Map<String, List<String>> advisoryIds = new LinkedHashMap<>();
        if (!queryable.isEmpty()) {
            try {
                queryAdvisories(queryable, advisoryIds, failures);
            } catch (IOException e) {
                failures.add(new Failed("(querybatch)", e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(new Failed("(querybatch)", "interrupted"));
            }
        }

        Map<String, Advisory> details = new HashMap<>();
        List<Vulnerable> vulnerable = new ArrayList<>();
        for (LockedArtifact artifact : queryable) {
            List<String> ids = advisoryIds.get(artifact.id());
            if (ids == null) {
                continue;  // the batch itself failed; already a named failure
            }
            List<Advisory> advisories = new ArrayList<>();
            for (String id : ids) {
                Advisory advisory = details.get(id);
                if (advisory == null) {
                    try {
                        advisory = fetchDetail(id, artifact.version());
                        details.put(id, advisory);  // one fetch per advisory per run
                    } catch (IOException e) {
                        failures.add(new Failed(artifact.id(),
                                "advisory " + id + ": " + e.getMessage()));
                        continue;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return 1;
                    }
                }
                if (advisory != null) {
                    advisories.add(advisory);
                }
            }
            if (!advisories.isEmpty()) {
                vulnerable.add(new Vulnerable(artifact.id(), artifact.version(), advisories));
            }
        }

        if (json) {
            printJson(vulnerable, notChecked, failures);
        } else {
            report(vulnerable, notChecked, failures, queryable.size());
        }
        return vulnerable.isEmpty() && failures.isEmpty() ? 0 : 1;
    }

    // ------------------------------------------------------------------
    // OSV
    // ------------------------------------------------------------------

    /** One batch POST; each result slot lines up with the query list. */
    private void queryAdvisories(List<LockedArtifact> artifacts,
                                 Map<String, List<String>> advisoryIds,
                                 List<Failed> failures)
            throws IOException, InterruptedException {
        var root = JsonUtils.object();
        var queries = JsonUtils.array();
        for (LockedArtifact artifact : artifacts) {
            var query = JsonUtils.object();
            var pkg = JsonUtils.object();
            pkg.put("name", artifact.id());
            pkg.put("ecosystem", "Maven");
            query.put("package", pkg);
            query.put("version", artifact.version());
            queries.add(query);
        }
        root.put("queries", queries);

        HttpRequest request = HttpRequest.newBuilder(URI.create(host() + "/v1/querybatch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            for (LockedArtifact artifact : artifacts) {
                failures.add(new Failed(artifact.id(),
                        "querybatch answered " + response.statusCode()));
            }
            return;
        }

        List<?> results;
        try {
            Object parsed = JsonUtils.parseObject(response.body()).toMap().get("results");
            results = parsed instanceof List<?> list ? list : List.of();
        } catch (RuntimeException e) {
            for (LockedArtifact artifact : artifacts) {
                failures.add(new Failed(artifact.id(), "unreadable querybatch response"));
            }
            return;
        }
        if (results.size() != artifacts.size()) {
            for (LockedArtifact artifact : artifacts) {
                failures.add(new Failed(artifact.id(),
                        "querybatch returned " + results.size() + " results for "
                                + artifacts.size() + " queries"));
            }
            return;
        }
        for (int i = 0; i < artifacts.size(); i++) {
            LockedArtifact artifact = artifacts.get(i);
            List<String> ids = new ArrayList<>();
            if (results.get(i) instanceof Map<?, ?> slot
                    && slot.get("vulns") instanceof List<?> vulns) {
                for (Object vuln : vulns) {
                    if (vuln instanceof Map<?, ?> entry && entry.get("id") != null) {
                        ids.add(String.valueOf(entry.get("id")));
                    }
                }
            }
            advisoryIds.put(artifact.id(), ids);
        }
    }

    /** One advisory document, reduced to the facts the report shows. */
    private Advisory fetchDetail(String id, String version)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(host() + "/v1/vulns/" + id))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("answered " + response.statusCode());
        }
        Map<String, Object> map;
        try {
            map = JsonUtils.parseObject(response.body()).toMap();
        } catch (RuntimeException e) {
            throw new IOException("unreadable advisory document");
        }
        return new Advisory(
                map.get("id") == null ? id : String.valueOf(map.get("id")),
                severity(map),
                summary(map),
                fixed(map, version),
                aliases(map));
    }

    /**
     * Severity has several shapes: OSV's own {@code database_specific.severity}
     * ("MODERATE"…), a CVSS vector array whose entries carry a {@code type}
     * ("CVSS_V4"…), or neither. The database's own label is the human one; the
     * CVSS type is the fallback and says only which scoring system was used.
     */
    private static String severity(Map<String, Object> map) {
        if (map.get("database_specific") instanceof Map<?, ?> database) {
            Object label = database.get("severity");
            if (label != null && !String.valueOf(label).isBlank()) {
                return String.valueOf(label);
            }
        }
        if (map.get("severity") instanceof List<?> vectors && !vectors.isEmpty()
                && vectors.getFirst() instanceof Map<?, ?> first) {
            Object type = first.get("type");
            if (type != null && !String.valueOf(type).isBlank()) {
                return String.valueOf(type);
            }
        }
        return null;
    }

    private static String summary(Map<String, Object> map) {
        Object summary = map.get("summary");
        if (summary != null && !String.valueOf(summary).isBlank()) {
            return String.valueOf(summary).strip();
        }
        Object details = map.get("details");
        if (details == null) {
            return null;
        }
        String text = String.valueOf(details).strip().lines().findFirst().orElse("");
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }

    /** The smallest {@code fixed} event above the current version. */
    private static String fixed(Map<String, Object> map, String version) {
        if (!(map.get("affected") instanceof List<?> affected)) {
            return null;
        }
        String best = null;
        for (Object entry : affected) {
            if (!(entry instanceof Map<?, ?> affectedEntry)
                    || !(affectedEntry.get("ranges") instanceof List<?> ranges)) {
                continue;
            }
            for (Object range : ranges) {
                if (!(range instanceof Map<?, ?> rangeEntry)
                        || !(rangeEntry.get("events") instanceof List<?> events)) {
                    continue;
                }
                for (Object event : events) {
                    if (!(event instanceof Map<?, ?> eventEntry)
                            || eventEntry.get("fixed") == null) {
                        continue;
                    }
                    String candidate = String.valueOf(eventEntry.get("fixed"));
                    if (Versions.compare(candidate, version) <= 0) {
                        continue;
                    }
                    if (best == null || Versions.compare(candidate, best) < 0) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static List<String> aliases(Map<String, Object> map) {
        if (!(map.get("aliases") instanceof List<?> aliases)) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object alias : aliases) {
            if (alias != null) {
                values.add(String.valueOf(alias));
            }
        }
        return List.copyOf(values);
    }

    private static String host() {
        String host = System.getProperty("mjm.osv.host", OSV_DEFAULT);
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    // ------------------------------------------------------------------
    // classification
    // ------------------------------------------------------------------

    /** OSV's Maven ecosystem is keyed by {@code group:artifact}; nothing else fits. */
    private static boolean isMavenCoordinate(LockedArtifact artifact) {
        return artifact.source().equals("central") && artifact.id().contains(":");
    }

    private static String reason(String source) {
        if (source.startsWith("gh:")) {
            return "mj-native";
        }
        if (source.equals("url")) {
            return "bare-url";
        }
        Repository repository;
        try {
            repository = Repository.parse(source);
        } catch (IllegalArgumentException notARepository) {
            return "named-repository";
        }
        if (repository.layout() == Repository.Layout.MJM) {
            return "mj-native";
        }
        return repository.isLocal() ? "local-repository" : "named-repository";
    }

    // ------------------------------------------------------------------
    // output
    // ------------------------------------------------------------------

    private void printJson(List<Vulnerable> vulnerable, List<NotChecked> notChecked,
                           List<Failed> failures) {
        var root = JsonUtils.object();

        var vulnArray = JsonUtils.array();
        for (Vulnerable entry : vulnerable) {
            var object = JsonUtils.object();
            object.put("id", entry.id());
            object.put("version", entry.version());
            var advisories = JsonUtils.array();
            for (Advisory advisory : entry.advisories()) {
                var doc = JsonUtils.object();
                doc.put("id", advisory.id());
                if (advisory.severity() != null) {
                    doc.put("severity", advisory.severity());
                }
                if (advisory.summary() != null) {
                    doc.put("summary", advisory.summary());
                }
                if (advisory.fixed() != null) {
                    doc.put("fixed", advisory.fixed());
                }
                var aliasArray = JsonUtils.array();
                for (String alias : advisory.aliases()) {
                    aliasArray.add(alias);
                }
                doc.put("aliases", aliasArray);
                advisories.add(doc);
            }
            object.put("advisories", advisories);
            vulnArray.add(object);
        }
        root.put("vulnerabilities", vulnArray);

        var skippedArray = JsonUtils.array();
        for (NotChecked entry : notChecked) {
            var object = JsonUtils.object();
            object.put("id", entry.id());
            object.put("reason", entry.reason());
            skippedArray.add(object);
        }
        root.put("notChecked", skippedArray);

        var failedArray = JsonUtils.array();
        for (Failed failure : failures) {
            var object = JsonUtils.object();
            object.put("id", failure.id());
            object.put("error", failure.error());
            failedArray.add(object);
        }
        root.put("failures", failedArray);
        System.out.println(root);
    }

    private void report(List<Vulnerable> vulnerable, List<NotChecked> notChecked,
                        List<Failed> failures, int checked) {
        if (vulnerable.isEmpty()) {
            // a failed query means the closure was not fully checked, so the
            // report must not say "clean" — only the checked part is clean
            System.out.println(failures.isEmpty()
                    ? "checked " + checked + " dependencies; no known vulnerabilities"
                    : "checked " + checked + " dependencies; " + failures.size()
                            + " could not be checked");
        } else {
            System.out.println("vulnerabilities:");
            for (Vulnerable entry : vulnerable) {
                System.out.println("  " + entry.id() + " " + entry.version());
                for (Advisory advisory : entry.advisories()) {
                    StringBuilder line = new StringBuilder("    ").append(advisory.id());
                    if (advisory.severity() != null) {
                        line.append("  [").append(advisory.severity()).append("]");
                    }
                    if (advisory.summary() != null) {
                        line.append("  ").append(advisory.summary());
                    }
                    if (advisory.fixed() != null) {
                        line.append("  (fixed in ").append(advisory.fixed()).append(")");
                    }
                    System.out.println(line);
                }
            }
        }
        if (!notChecked.isEmpty()) {
            System.out.println("not checked:");
            for (NotChecked entry : notChecked) {
                System.out.println("  " + entry.id() + " — " + entry.reason());
            }
        }
        if (!failures.isEmpty()) {
            System.out.println("failures:");
            for (Failed failure : failures) {
                System.out.println("  " + failure.id() + " — " + failure.error());
            }
        }
    }
}
