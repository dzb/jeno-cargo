package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.project.Requirement;
import com.jujin.jeno.store.LockFileReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jeno remove <id>} — delete one dependency declaration and re-lock.
 *
 * <p>The counterpart of {@code jeno add}: before this command existed, dropping
 * a dependency meant hand-editing jeno.toml and remembering to re-lock. It
 * removes the declaration from {@code [deps]}, {@code [deps-dev]}, or
 * {@code [deps-api]} without touching any other section —
 * comments, layout and every other line survive — then refreshes jeno.lock so
 * the artifact leaves the graph immediately.
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
    public Command.Category category() {
        return Command.Category.PROJECT;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.OPTIONAL)
                .writes(CommandCapabilities.WriteScope.PROJECT,
                        CommandCapabilities.WriteScope.LOCKS,
                        CommandCapabilities.WriteScope.CACHE)
                .workspace(CommandCapabilities.WorkspaceMode.MODULE)
                .destructive()
                .build();
    }

    @Override
    public int execute(List<String> args) {
        ProjectFiles.rejectLegacy(projectDir);
        boolean json = args.contains("--json");
        List<String> cleaned = args.stream().filter(arg -> !arg.equals("--json")).toList();
        if (!json) {
            return executeHuman(cleaned);
        }
        if (cleaned.size() != 1) {
            JsonResult.error("remove", 2, "USAGE",
                    "remove needs exactly one dependency id — jeno remove \"group:artifact\"")
                    .emit(2);
            return 2;
        }

        Path moduleDir = projectDir;
        List<String> moduleArgs = cleaned;
        if (Workspaces.rootAt(projectDir).isPresent()) {
            WorkspaceRun.Run run;
            try {
                run = WorkspaceRun.root(projectDir, cleaned);
            } catch (Workspaces.UsageException e) {
                JsonResult.error("remove", 2, "USAGE", e.getMessage()).emit(2);
                return 2;
            } catch (Workspaces.WorkspaceException e) {
                JsonResult.error("remove", 1, "WORKSPACE_INVALID", e.getMessage()).emit(1);
                return 1;
            }
            if (run == null || run.module() == null) {
                JsonResult.error("remove", 2, "MODULE_REQUIRED",
                        "this directory is a workspace root — say which module: --module <path>")
                        .emit(2);
                return 2;
            }
            moduleDir = run.module();
            moduleArgs = run.args();
        } else if (hasModuleOption(cleaned)) {
            JsonResult.error("remove", 2, "MODULE_WITHOUT_WORKSPACE",
                    "--module is only used at a workspace root").emit(2);
            return 2;
        }

        String id = moduleArgs.getFirst();
        Path targetDir = moduleDir;
        List<String> targetArgs = moduleArgs;
        JsonCapture.Result captured = JsonCapture.run(() ->
                new RemoveCommand(lockService, targetDir).executeHuman(targetArgs));
        if (captured.exitCode() != 0) {
            JsonResult.error("remove", captured.exitCode(),
                    captured.exitCode() == 2 ? "USAGE" : "REMOVE_FAILED", captured.message())
                    .emit(captured.exitCode());
            return captured.exitCode();
        }
        try {
            int artifacts = LockFileReader.read(
                    Files.readString(moduleDir.resolve("jeno.lock"))).size();
            JsonObject removed = JsonUtils.object();
            removed.put("id", id);
            JsonResult.start("remove")
                    .workspace(moduleDir, "single")
                    .put("mode", "mutate")
                    .put("removed", removed)
                    .put("manifest", moduleDir.resolve("jeno.toml")
                            .toAbsolutePath().normalize().toString())
                    .put("lock", moduleDir.resolve("jeno.lock")
                            .toAbsolutePath().normalize().toString())
                    .put("artifactCount", artifacts)
                    .put("lockUpdated", true)
                    .emit(0);
            return 0;
        } catch (IOException | RuntimeException e) {
            JsonResult.error("remove", 1, "RESULT_UNREADABLE", e.getMessage()).emit(1);
            return 1;
        }
    }

    private int executeHuman(List<String> args) {
        Integer workspace = WorkspaceRun.requireModule(projectDir, args,
                (dir, rest) -> new RemoveCommand(lockService, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        if (args.size() != 1) {
            System.err.println(Ui.errPrefix() + "remove needs exactly one dependency id — "
                    + "jeno remove \"group:artifact\"");
            return 2;
        }
        String id = args.getFirst();

        // same project lock as add/build/test/clean — see AddCommand
        return ProjectLock.with(projectDir, () -> remove(id));
    }

    private static boolean hasModuleOption(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("--module") || arg.startsWith("--module="));
    }

    private int remove(String id) {
        Path file = projectDir.resolve("jeno.toml");
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "no jeno.toml in " + projectDir
                    + " — run jeno init to create one");
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
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        if (!declared.contains(id)) {
            System.err.println(Ui.errPrefix() + "'" + id + "' is not declared — declared: "
                    + (declared.isEmpty() ? "(none)" : String.join(", ", declared)));
            return 1;
        }

        String updated = removeLine(text, id);
        if (updated.equals(text)) {
            System.err.println(Ui.errPrefix() + "could not find the declaration line for '" + id
                    + "' — the file may use an unusual layout; edit it by hand");
            return 1;
        }
        LockService.Prepared prepared;
        try {
            prepared = lockService.prepare(updated);
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        try {
            lockService.commit(projectDir, prepared);
        } catch (RuntimeException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        System.out.println(Ui.ok("removed " + id + " — locked "
                + prepared.graph().artifacts().size() + " artifact(s) into jeno.lock"));
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
        boolean removed = false;
        for (int i = 0; i < lines.size(); ) {
            String stripped = lines.get(i).strip();
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                section = stripped;
                i++;
                continue;
            }
            if (section != null && isDepsSection(section) && declares(lines.get(i), id)) {
                lines.remove(i);
                removed = true;
                continue;
            }
            i++;
        }
        return removed ? String.join("\n", lines) + "\n" : text;
    }

    private static boolean isDepsSection(String header) {
        return header.equals("[deps]") || header.equals("[deps-api]")
                || header.equals("[deps-dev]");
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
