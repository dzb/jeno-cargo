package com.jujin.jeno.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Root-directory orchestration for the verbs that make sense across a whole
 * workspace: each module is run in dependency order, under its own header, so
 * the same command in the root means "all of them" and in a module means "just
 * this one".
 *
 * <p>Not a transaction: a module that succeeded keeps its output even if a later
 * one fails. Build-like verbs stop at the first failure (the next module would
 * only build on top of broken ground); test and clean keep going and report who
 * failed, because their results are independent.
 *
 * <p>A module dir is never a workspace root, so instantiating the same command
 * there cannot re-enter orchestration — the child runs the ordinary single-project
 * path.
 */
final class WorkspaceRun {

    /** Runs the verb for one module; returns the exit code the module reported. */
    interface Child {
        int run(Path moduleDir, List<String> args);
    }

    /** The root's workspace, the selected module (null = all), and the cleaned args. */
    record Run(Workspaces.Workspace workspace, Path module, List<String> args) {}

    private WorkspaceRun() {}

    /** The workspace if {@code projectDir} is its root, else null. */
    static Run root(Path projectDir, List<String> args) {
        return root(projectDir, args, false);
    }

    /**
     * @param stopAtFirstPositional for {@code run}: the first argument that is not
     *        a flag is the target, and everything from it on belongs to the
     *        program — a {@code --module} after it must not be consumed here.
     */
    static Run root(Path projectDir, List<String> args, boolean stopAtFirstPositional) {
        var workspace = Workspaces.rootAt(projectDir).orElse(null);
        if (workspace == null) {
            return null;
        }
        String requested = null;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--module")) {
                if (i + 1 >= args.size()) {
                    throw new Workspaces.UsageException("--module needs a module path");
                }
                requested = args.get(++i);
            } else if (arg.startsWith("--module=")) {
                requested = arg.substring("--module=".length());
            } else if (stopAtFirstPositional && !arg.startsWith("-")) {
                rest.addAll(args.subList(i, args.size()));
                break;
            } else {
                rest.add(arg);
            }
        }
        return new Run(workspace, requested == null ? null
                : module(workspace, requested), List.copyOf(rest));
    }

    /** The module named by {@code --module}, checked against the workspace's list. */
    static Path module(Workspaces.Workspace workspace, String requested) {
        Path candidate = workspace.root().resolve(requested).normalize();
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (java.io.IOException e) {
            throw new Workspaces.UsageException("'" + requested + "' is not a module of this"
                    + " workspace — known: " + known(workspace));
        }
        for (Path module : workspace.modules()) {
            if (module.equals(real)) {
                return module;
            }
        }
        throw new Workspaces.UsageException("'" + requested + "' is not a module of this"
                + " workspace — known: " + known(workspace));
    }

    private static String known(Workspaces.Workspace workspace) {
        List<String> names = new ArrayList<>();
        for (Path module : workspace.modules()) {
            names.add(Workspaces.display(workspace.root(), module));
        }
        return String.join(", ", names);
    }

    /**
     * Runs a build-like verb across the workspace. Returns null when this
     * directory is not a workspace root, so the caller keeps its usual path.
     */
    static Integer orchestrate(Path projectDir, List<String> args, String label,
                               boolean keepGoing, Child child) {
        Run run;
        try {
            run = root(projectDir, args);
        } catch (Workspaces.UsageException e) {
            return usage(e.getMessage());
        } catch (Workspaces.WorkspaceException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        if (run == null) {
            if (namesModule(args)) {
                System.err.println(Ui.errPrefix() + "--module is only used at a workspace root");
                return 2;
            }
            return null;
        }
        List<Path> modules;
        try {
            modules = run.module() != null
                    ? List.of(run.module())
                    : Workspaces.topological(run.workspace());
        } catch (Workspaces.WorkspaceException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        List<String> failed = new ArrayList<>();
        int passed = 0;
        for (Path module : modules) {
            String name = Workspaces.display(run.workspace().root(), module);
            System.out.println(name);
            int code;
            try {
                code = withIndentedOutput(() -> inModule(module,
                        () -> child.run(module, run.args())));
            } catch (ShutdownAfterBuildOutput e) {
                throw e;  // the process is about to exit on purpose
            } catch (RuntimeException e) {
                System.err.println(Ui.errPrefix() + e.getMessage());
                code = 1;
            }
            if (code == 0) {
                passed++;
                continue;
            }
            failed.add(name);
            if (!keepGoing) {
                System.out.println(label + ": " + passed + ", failed: " + failed.size());
                return code;
            }
        }
        System.out.println(label + ": " + passed + ", failed: " + failed.size());
        if (!failed.isEmpty()) {
            System.out.println("failed modules: " + String.join(", ", failed));
            return 1;
        }
        return 0;
    }

    /**
     * A verb that needs one module (add/remove/run/info/tree/why/classpath).
     * Returns null when this directory is not a workspace root.
     */
    static Integer requireModule(Path projectDir, List<String> args, Child child) {
        return requireModule(projectDir, args, false, child);
    }

    static Integer requireModule(Path projectDir, List<String> args,
                                 boolean stopAtFirstPositional, Child child) {
        boolean namesModule = namesModuleInPrefix(args, stopAtFirstPositional);
        if (!namesModule && Workspaces.rootAt(projectDir).isEmpty()) {
            return null;  // ordinary single-project invocation
        }
        if (!namesModule) {
            System.err.println(Ui.errPrefix() + "this directory is a workspace root — say which module:"
                    + " --module <path>");
            return 2;
        }
        Run run;
        try {
            run = root(projectDir, args, stopAtFirstPositional);
        } catch (Workspaces.UsageException e) {
            return usage(e.getMessage());
        } catch (Workspaces.WorkspaceException e) {
            System.err.println(Ui.errPrefix() + e.getMessage());
            return 1;
        }
        if (run == null) {
            System.err.println(Ui.errPrefix() + "--module is only used at a workspace root");
            return 2;
        }
        return inModule(run.module(), () -> child.run(run.module(), run.args()));
    }

    /** Whether the argument list names a module at all ({@code --module} / {@code =}). */
    private static boolean namesModule(List<String> args) {
        return args.stream()
                .anyMatch(arg -> arg.equals("--module") || arg.startsWith("--module="));
    }

    /**
     * For {@code run}, only the prefix before the target is mjm's: a
     * {@code --module} after the target belongs to the program.
     */
    private static boolean namesModuleInPrefix(List<String> args, boolean stopAtFirstPositional) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--module") || arg.startsWith("--module=")) {
                return true;
            }
            if (stopAtFirstPositional && !arg.startsWith("-")) {
                return false;
            }
        }
        return false;
    }

    /**
     * Runs one module with the process pointed at it: a relative {@code local:}
     * repository or a relative {@code file:} lock row means "relative to this
     * module", exactly as it does when the module is the working directory.
     * The previous root is restored even when the command throws.
     */
    private static int inModule(Path module, java.util.function.IntSupplier body) {
        Path previous = com.jujin.jeno.resolve.ArtifactLocations.projectRoot();
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(module);
        try {
            return body.getAsInt();
        } finally {
            com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(previous);
        }
    }

    private static int usage(String message) {
        System.err.println(Ui.errPrefix() + message);
        return 2;
    }

    /** Runs the child with its stdout captured, then prints it indented under its header. */
    private static int withIndentedOutput(java.util.function.IntSupplier body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            return body.getAsInt();
        } finally {
            System.setOut(original);
            String text = buffer.toString(StandardCharsets.UTF_8);
            if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            for (String line : text.split("\n", -1)) {
                System.out.println(line.isEmpty() ? "" : "  " + line);
            }
        }
    }
}
