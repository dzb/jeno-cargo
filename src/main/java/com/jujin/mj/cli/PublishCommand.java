package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.project.MjProject;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.project.Requirement;
import com.jujin.mj.project.Species;
import com.jujin.mj.resolve.ArtifactLocations;
import com.jujin.mj.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code mj publish [target]} — the decentralized release (DESIGN.md §7.2):
 * generates the three-piece artifact next to the project and prints the
 * upload checklist. Publishing is "put files where a URL can reach" — a
 * GitHub/Gitee release, an OSS bucket, any static server — so mj itself
 * never needs credentials in V1:
 *
 * <pre>
 *   dist/mylib-1.2.0.jar          the build output, zipped
 *   dist/mylib-1.2.0.mj.toml      the descriptor: facts only (deps, java)
 *   dist/mylib-1.2.0.sha256       the jar's digest, verified by every consumer
 * </pre>
 *
 * <p>The optional target ({@code gh:owner/repo} or {@code gitee:owner/repo})
 * prints the exact release URLs the files belong at. Native upload through
 * the release APIs is V1.1 ({@code MJ_GITHUB_TOKEN} / {@code MJ_GITEE_TOKEN}).
 */
public final class PublishCommand implements Command {

    private static final String CLASSES = "target/classes";

    private final Path projectDir;

    public PublishCommand(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public String summary() {
        return "generate the release triple into dist/ (+ upload checklist for gh:/gitee:)";
    }

    @Override
    public int execute(List<String> args) {
        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mj.toml")));
        } catch (IOException e) {
            System.err.println("mj: cannot read mj.toml in " + projectDir);
            return 1;
        }

        String name = project.name();
        String version = project.version();
        String jarName = name + "-" + version + ".jar";
        String descriptorName = name + "-" + version + ".mj.toml";
        String shaName = name + "-" + version + ".sha256";

        Path classes = projectDir.resolve(CLASSES);
        if (!Files.isDirectory(classes)) {
            System.err.println("mj: " + CLASSES + " is missing — run mj build first");
            return 1;
        }

        // A repo-bound dependency cannot travel in a public descriptor: the
        // consumer's mj.toml would have no way to reach it.
        for (var requirement : project.requirements()) {
            if (requirement.species() == Species.CENTRAL && requirement.repository() != null) {
                System.err.println("mj: '" + requirement.id() + "' is bound to repository '"
                        + requirement.repository() + "' — descriptors carry Central, gh:, gitee: "
                        + "and URL dependencies only; publish that dependency to the same host "
                        + "as this library, or drop it");
                return 1;
            }
        }

        Path dist = projectDir.resolve("dist");
        try {
            Files.createDirectories(dist);
        } catch (IOException e) {
            System.err.println("mj: cannot create dist/");
            return 1;
        }

        Path jarFile = dist.resolve(jarName);
        int exit = jar(classes, jarFile);
        if (exit != 0) {
            return exit;
        }

        try {
            Files.writeString(dist.resolve(descriptorName), descriptor(project));
            Files.writeString(dist.resolve(shaName),
                    ArtifactStore.sha256(jarFile) + "\n");
        } catch (IOException e) {
            System.err.println("mj: cannot write the descriptor or sha256 into dist/");
            return 1;
        }

        System.out.println("published the release triple into dist/:");
        System.out.println("  " + jarFile);
        System.out.println("  " + dist.resolve(descriptorName));
        System.out.println("  " + dist.resolve(shaName));
        if (!args.isEmpty()) {
            printChecklist(args.getFirst(), name, version);
        } else {
            System.out.println("upload the three files wherever a URL can reach — a GitHub/Gitee "
                    + "release, an OSS bucket, any static server — and consumers add\n  "
                    + "\"" + name + "\" = \"gh:<owner>/" + name + "@v" + version + "\"");
        }
        return 0;
    }

    /** The descriptor is the project's own dependency facts, verbatim. */
    private static String descriptor(MjProject project) {
        StringBuilder out = new StringBuilder();
        out.append("[project]\n");
        out.append("name = \"").append(project.name()).append("\"\n");
        out.append("version = \"").append(project.version()).append("\"\n");
        if (project.javaVersion() != null) {
            out.append("\n[java]\nversion = ").append(project.javaVersion()).append('\n');
        }
        if (!project.requirements().isEmpty()) {
            out.append("\n[deps]\n");
            for (Requirement requirement : project.requirements()) {
                out.append("\"").append(requirement.id()).append("\" = \"")
                        .append(requirementValue(requirement)).append("\"\n");
            }
        }
        return out.toString();
    }

    private static String requirementValue(Requirement requirement) {
        return switch (requirement.species()) {
            case CENTRAL -> requirement.version();
            case GH, GITEE -> requirement.origin() + "@" + requirement.tag();
            case URL -> requirement.url();
        };
    }

    private static void printChecklist(String target, String name, String version) {
        if (!target.startsWith("gh:") && !target.startsWith("gitee:")) {
            System.err.println("mj: unknown publish target '" + target
                    + "' — use gh:owner/repo or gitee:owner/repo");
            return;
        }
        String host = target.startsWith("gh:")
                ? ArtifactLocations.githubHost()
                : ArtifactLocations.giteeHost();
        String owner = target.substring(target.indexOf(':') + 1, target.indexOf('/'));
        String repo = target.substring(target.indexOf('/') + 1);
        String tag = "v" + version;
        String base = host + "/" + owner + "/" + repo + "/releases/download/" + tag + "/";
        System.out.println("create release " + tag + " on " + host + "/" + owner + "/" + repo
                + " and upload as:");
        System.out.println("  " + base + name + "-" + version + ".jar");
        System.out.println("  " + base + name + "-" + version + ".mj.toml");
        System.out.println("  " + base + name + "-" + version + ".sha256");
        System.out.println("consumers then add: \"" + name + "\" = \"" + target + "@" + tag + "\"");
    }

    /** Zips {@code classes} into {@code jarFile} with the JDK's jar tool. */
    private static int jar(Path classes, Path jarFile) {
        List<String> command = List.of("jar", "--create", "--file", jarFile.toString(),
                "--no-manifest", "-C", classes.toString(), ".");
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            return process.waitFor();
        } catch (IOException e) {
            System.err.println("mj: cannot run jar — is a JDK on the PATH?");
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }
}
