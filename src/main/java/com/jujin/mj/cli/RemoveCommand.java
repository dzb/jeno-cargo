package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.project.Requirement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code mj remove <id>} — delete one dependency declaration and re-lock.
 *
 * <p>The counterpart of {@code mj add}: before this command existed, dropping
 * a dependency meant hand-editing mj.toml and remembering to re-lock. It
 * removes exactly the one declaration line from {@code [deps]},
 * {@code [dev-deps]}, or their {@code .{repo}} groups — comments, layout and
 * every other line survive — then refreshes mj.lock so the artifact leaves
 * the graph immediately.
 *
 * <p>An id that is not declared is an error naming the declared set, never a
 * silent no-op.
 */
public final class RemoveCommand implements Command {

    private final LockService lockService;
    private final Path projectDir;

    @Inject
    public RemoveCommand(LockService lockService, Path projectDir) {
        this.lockService = lockService;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "remove";
    }

    @Override
    public String summary() {
        return "remove a dependency and re-lock";
    }
    @Override
    public String usage() {
        return "<id>";
    }


    @Override
    public int execute(List<String> args) {
        if (args.size() != 1) {
            System.err.println("mj: mj remove needs exactly one dependency id — "
                    + "mj remove \"group:artifact\"");
            return 2;
        }
        String id = args.getFirst();

        Path file = projectDir.resolve("mj.toml");
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            System.err.println("mj: cannot read mj.toml in " + projectDir);
            return 1;
        }

        List<String> declared = new ArrayList<>();
        try {
            var project = ProjectReader.parse(text);
            for (Requirement requirement : project.requirements()) {
                declared.add(requirement.id());
            }
            for (Requirement requirement : project.devRequirements()) {
                declared.add(requirement.id());
            }
        } catch (RuntimeException e) {
            System.err.println("mj: " + e.getMessage());
            return 1;
        }
        if (!declared.contains(id)) {
            System.err.println("mj: '" + id + "' is not declared — declared: "
                    + (declared.isEmpty() ? "(none)" : String.join(", ", declared)));
            return 1;
        }

        String updated = removeLine(text, id);
        if (updated.equals(text)) {
            System.err.println("mj: could not find the declaration line for '" + id
                    + "' — the file may use an unusual layout; edit it by hand");
            return 1;
        }
        try {
            ProjectReader.parse(updated);  // the edited file must still be a valid project
        } catch (RuntimeException e) {
            System.err.println("mj: removing '" + id + "' would break mj.toml — " + e.getMessage());
            return 1;
        }
        try {
            Files.writeString(file, updated);
        } catch (IOException e) {
            System.err.println("mj: cannot write mj.toml");
            return 1;
        }

        var graph = lockService.refresh(projectDir);
        System.out.println("removed " + id + " — locked "
                + graph.artifacts().size() + " artifact(s) into mj.lock");
        return 0;
    }

    /**
     * Deletes the declaration line for {@code id} from any {@code [deps*}
     * section, leaving every other line (comments included) untouched. Both
     * spellings of a key are recognized: quoted and bare.
     */
    static String removeLine(String text, String id) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        String section = null;
        for (int i = 0; i < lines.size(); i++) {
            String stripped = lines.get(i).strip();
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                section = stripped;
                continue;
            }
            if (section == null || !isDepsSection(section)) {
                continue;
            }
            if (declares(lines.get(i), id)) {
                lines.remove(i);
                return String.join("\n", lines) + "\n";
            }
        }
        return text;
    }

    private static boolean isDepsSection(String header) {
        return header.equals("[deps]") || header.equals("[dev-deps]")
                || header.startsWith("[deps.") || header.startsWith("[dev-deps.");
    }

    /** Whether one line is {@code <key> = <value>} for this id. */
    private static boolean declares(String line, String id) {
        String stripped = line.strip();
        int equals = stripped.indexOf('=');
        if (equals < 0 || stripped.startsWith("#")) {
            return false;
        }
        String key = stripped.substring(0, equals).strip();
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        return key.equals(id);
    }
}
