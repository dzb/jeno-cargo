package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.pom.PomMigration;
import com.jujin.mj.project.ProjectReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code mj init} — start a project, or migrate one from Maven.
 *
 * <pre>
 *   mj init                    skeleton mj.toml for the current directory
 *   mj init --from-pom         translate ./pom.xml into mj.toml (reviewed, not
 *                              imported: every unrepresentable fact is printed)
 * </pre>
 *
 * <p>The migrated file is validated through {@link ProjectReader} before it is
 * written — a translation that mj itself would refuse never lands on disk.
 * An existing mj.toml is never overwritten without {@code --force}.
 */
public final class InitCommand implements Command {

    private final Path projectDir;

    @Inject
    public InitCommand(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "init";
    }

    @Override
    public String summary() {
        return "create mj.toml — a skeleton, or --from-pom to migrate a Maven project";
    }

    @Override
    public int execute(List<String> args) {
        boolean fromPom = false;
        boolean force = false;
        for (String arg : args) {
            switch (arg) {
                case "--from-pom" -> fromPom = true;
                case "--force" -> force = true;
                default -> {
                    System.err.println("mj: unknown init option '" + arg + "'");
                    return 2;
                }
            }
        }

        Path target = projectDir.resolve("mj.toml");
        if (Files.exists(target) && !force) {
            System.err.println("mj: " + target + " already exists — pass --force to overwrite");
            return 1;
        }

        String toml;
        if (fromPom) {
            Path pom = projectDir.resolve("pom.xml");
            if (!Files.exists(pom)) {
                System.err.println("mj: no pom.xml in " + projectDir + " to migrate");
                return 1;
            }
            PomMigration.Result result;
            try {
                result = PomMigration.translate(Files.readString(pom));
            } catch (IOException e) {
                System.err.println("mj: cannot read " + pom);
                return 1;
            } catch (RuntimeException e) {
                System.err.println("mj: " + e.getMessage());
                return 1;
            }
            for (String note : result.notes()) {
                System.out.println("  note: " + note);
            }
            toml = result.mjToml();
        } else {
            toml = skeleton(nameOf(projectDir));
        }

        // never write a file mj would refuse to read
        try {
            ProjectReader.parse(toml);
        } catch (RuntimeException e) {
            System.err.println("mj: the generated mj.toml would not parse — " + e.getMessage());
            return 1;
        }

        try {
            Files.writeString(target, toml);
        } catch (IOException e) {
            System.err.println("mj: cannot write " + target);
            return 1;
        }

        System.out.println("created " + target
                + (fromPom ? " (migrated from pom.xml)" : ""));
        System.out.println("next: mj add \"group:artifact\" <version>   then   mj lock");
        return 0;
    }

    /** The skeleton: a directory name is a valid mj project name, or gets one. */
    private static String skeleton(String name) {
        return """
            [project]
            name = "%s"
            version = "0.1.0"

            [java]
            version = 25

            [deps]
            """.formatted(name);
    }

    /** A lowercase, dash-separated project name derived from the directory. */
    static String nameOf(Path dir) {
        Path fileName = dir.getFileName();
        String raw = fileName == null ? "app" : fileName.toString();
        String candidate = raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (candidate.isEmpty() || !candidate.matches("[a-z].*")) {
            return "app";
        }
        return candidate;
    }
}
