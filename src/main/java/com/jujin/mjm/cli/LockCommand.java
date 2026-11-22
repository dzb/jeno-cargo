package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code mjm lock}: the shell around {@link LockService} — resolve, download,
 * pin (SPEC.md §4).
 */
public final class LockCommand implements Command {

    private final LockService lockService;
    private final Path projectDir;

    @Inject
    public LockCommand(LockService lockService, Path projectDir) {
        this.lockService = lockService;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "lock";
    }

    @Override
    public String summary() {
        return "resolve dependencies, download into the store, and pin mjm.lock";
    }

    @Override
    public int execute(List<String> args) {
        // lock writes both files, so it queues with add/remove/build/test/clean
        return ProjectLock.with(projectDir, () -> {
            var graph = lockService.refresh(projectDir);
            System.out.println("locked " + graph.artifacts().size()
                    + " artifact(s) into mjm.lock");
            return 0;
        });
    }
}
