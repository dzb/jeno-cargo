package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code mj lock}: the shell around {@link LockService} — resolve, download,
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
        return "resolve dependencies, download into the store, write mj.lock";
    }

    @Override
    public int execute(List<String> args) {
        var graph = lockService.refresh(projectDir);
        System.out.println("locked " + graph.artifacts().size() + " artifact(s) into mj.lock");
        return 0;
    }
}
