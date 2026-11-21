package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.pom.PomSource;
import com.jujin.mjm.project.DescriptorSource;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.resolve.ArtifactLocations;
import com.jujin.mjm.resolve.DigestSource;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.mjm.store.ArtifactStore;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code mjm add &lt;key&gt; [value]}: appends one dependency to mjm.toml's
 * {@code [deps]} and re-locks. Without a value:
 *
 * <ul>
 *   <li>a Central coordinate ({@code group:artifact}) lists every version
 *       from the repository's {@code maven-metadata.xml}</li>
 *   <li>a bare name searches the Central index ({@code mjm add jackson}) —
 *       the fuzzy search Q1 scheduled for M5</li>
 * </ul>
 */
public final class AddCommand implements Command {

    private final LockService lockService;
    private final ArtifactStore store;
    private final Path projectDir;
    private final HttpClient http = HttpClient.newBuilder().build();

    @Inject
    public AddCommand(LockService lockService, ArtifactStore store, Path projectDir) {
        this.lockService = lockService;
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "add";
    }

    @Override
    public String usage() {
        return "<key> [value]";
    }

    @Override
    public String summary() {
        return "add a dependency and re-lock (no value: list versions or search)";
    }

    @Override
    public int execute(List<String> args) {
        if (args.isEmpty() || args.size() > 2) {
            System.err.println("mjm: mj add needs a key and an optional value — "
                    + "mjm add \"group:artifact\" 1.2.3 | mj add mylib gh:owner/repo@v1.0.0");
            return 2;
        }
        String key = args.get(0);
        String value = args.size() == 2 ? args.get(1) : null;

        String mjToml;
        try {
            mjToml = Files.readString(projectDir.resolve("mjm.toml"));
        } catch (IOException e) {
            System.err.println("mjm: cannot read mjm.toml in " + projectDir);
            return 1;
        }

        if (value == null) {
            return discover(key, mjToml);
        }

        com.jujin.mjm.project.Requirement requirement;
        try {
            requirement = ProjectReader.parseRequirement(key, value);  // shape validation, SPEC.md §2.1
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }
        String updated = appendToDeps(mjToml, key, value);
        try {
            ProjectReader.parse(updated);  // the edited file must still be a valid project
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }
        try {
            Files.writeString(projectDir.resolve("mjm.toml"), updated);
        } catch (IOException e) {
            System.err.println("mjm: cannot write mjm.toml");
            return 1;
        }

        var graph = lockService.refresh(projectDir);
        System.out.println("added " + requirement.id() + "@" + value + " — locked "
                + graph.artifacts().size() + " artifact(s) into mjm.lock");
        return 0;
    }

    /** One of the two "no value" answers: list versions, or search the index. */
    private int discover(String key, String mjToml) {
        if (key.contains(":")) {
            List<String> versions = versions(mjToml, key);
            if (versions.isEmpty()) {
                System.err.println("mjm: no versions found for " + key);
                return 1;
            }
            System.out.println("versions of " + key + " (newest last):");
            for (String version : versions) {
                System.out.println("  " + version);
            }
            System.out.println("choose one: mj add \"" + key + "\" <version>");
            return 1;
        }
        return search(key);
    }

    /** maven-metadata.xml's version list, straight from the repository. */
    private List<String> versions(String mjToml, String coordinate) {
        String base = ArtifactLocations.centralBase();
        // an existing named repository could override; V1 lists from Central
        String group = coordinate.substring(0, coordinate.indexOf(':'));
        String artifactId = coordinate.substring(coordinate.indexOf(':') + 1);
        String url = base + group.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
        String xml = fetch(url);
        List<String> versions = new ArrayList<>();
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            boolean inVersion = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    inVersion = "version".equals(reader.getLocalName());
                } else if (event == XMLStreamReader.CHARACTERS && inVersion) {
                    String text = reader.getText().trim();
                    if (!text.isEmpty()) {
                        versions.add(text);
                    }
                } else if (event == XMLStreamReader.END_ELEMENT) {
                    inVersion = false;
                }
            }
            reader.close();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read maven-metadata.xml from " + url, e);
        }
        return versions;
    }

    /** Central's index search: bare name in, group:artifact + latest version out. */
    private int search(String name) {
        String host = System.getProperty("mjm.search.host", "https://search.maven.org");
        String url = host + "/solrsearch/select?q=a:" + name + "&rows=20&wt=json";
        String body = fetch(url);
        Object response = JsonUtils.parseObject(body).toMap().get("response");
        if (!(response instanceof Map<?, ?> responseMap)
                || !(responseMap.get("docs") instanceof List<?> docs)) {
            System.err.println("mjm: unexpected search response from " + host);
            return 1;
        }
        System.out.println("search '" + name + "' on " + host + ":");
        for (Object docObject : docs) {
            if (docObject instanceof Map<?, ?> doc) {
                System.out.println("  " + doc.get("id") + "  (latest: " + doc.get("latestVersion")
                        + ")");
            }
        }
        System.out.println("add one: mj add \"<group:artifact>\" <version>");
        return 1;
    }

    private String fetch(String url) {
        if (url.startsWith("file://")) {
            try {
                return Files.readString(Path.of(URI.create(url)));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + url, e);
            }
        }
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(url + " answered " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("fetch failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fetch interrupted: " + url, e);
        }
    }

    /**
     * Appends one {@code key = value} line to the {@code [deps]} table —
     * before the next section header, never inside a sub-table. The file's
     * comments and layout survive; only one line appears.
     */
    static String appendToDeps(String text, String key, String value) {
        List<String> lines = text.lines().collect(java.util.stream.Collectors.toList());
        int depsLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("[deps]")) {
                depsLine = i;
                break;
            }
        }
        String line = "\"" + key + "\" = \"" + value + "\"";
        if (depsLine < 0) {
            lines.add("");
            lines.add("[deps]");
            lines.add(line);
            return String.join("\n", lines) + "\n";
        }
        int insertAt = depsLine + 1;
        while (insertAt < lines.size() && !lines.get(insertAt).strip().startsWith("[")) {
            insertAt++;
        }
        lines.add(insertAt, line);
        return String.join("\n", lines) + "\n";
    }
}
