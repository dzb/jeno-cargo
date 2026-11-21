package com.jujin.mj.cli;

import com.jujin.freeway.flow.FlowEngine;
import com.jujin.freeway.flow.TaskHandler;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Binder;
import com.jujin.mj.pom.PomSource;
import com.jujin.mj.project.DescriptorSource;
import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.StoreDescriptorSource;
import com.jujin.mj.store.StorePomSource;

import java.nio.file.Path;

/**
 * mj's composition (DESIGN.md §9): the store and its three source seams bound
 * once, the commands contributed as extensions against the working directory
 * (the project root), the runner assembled through constructor injection. mj
 * itself is the flagship Freeway application — the tool that manages
 * dependencies is assembled through dependency injection.
 */
public final class MjModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        binder.bind(ArtifactStore.class).to(c -> ArtifactStore.userStore());
        binder.bind(PomSource.class).to(StorePomSource.class);
        binder.bind(DescriptorSource.class).to(StoreDescriptorSource.class);
        binder.bind(LockService.class).to(LockService.class);
        binder.bind(CommandRunner.class).to(CommandRunner.class);
        Path projectDir = Path.of("").toAbsolutePath();
        binder.contribute(Command.class).addDeferred("lock",
                c -> new LockCommand(c.get(LockService.class), projectDir));
        binder.contribute(Command.class).addDeferred("build",
                c -> new BuildCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("run",
                c -> new RunCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("publish",
                c -> new PublishCommand(projectDir));
        binder.contribute(Command.class).addDeferred("package",
                c -> new PackageCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("dist",
                c -> new DistCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("test",
                c -> new TestCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("tree",
                c -> new TreeCommand(c.get(ArtifactStore.class), c.get(PomSource.class),
                        c.get(DescriptorSource.class), projectDir));
        binder.contribute(Command.class).addDeferred("why",
                c -> new WhyCommand(c.get(ArtifactStore.class), c.get(PomSource.class),
                        c.get(DescriptorSource.class), projectDir));
        binder.contribute(Command.class).addDeferred("add",
                c -> new AddCommand(c.get(LockService.class), c.get(ArtifactStore.class),
                        projectDir));
        binder.contribute(Command.class).addDeferred("remove",
                c -> new RemoveCommand(c.get(LockService.class), projectDir));
        binder.contribute(Command.class).addDeferred("init",
                c -> new InitCommand(projectDir));
        binder.contribute(Command.class).addDeferred("update",
                c -> new UpdateCommand());
        binder.contribute(Command.class).addDeferred("release",
                c -> new ReleaseCommand(c.get(FlowEngine.class)));
        // the release lifecycle stages — the flow graph resolves them by id
        binder.bind(TaskHandler.class)
                .to(c -> new ReleaseCommand.Lock(c.get(LockService.class), projectDir))
                .id("release-lock");
        binder.bind(TaskHandler.class)
                .to(c -> new ReleaseCommand.Build(c.get(ArtifactStore.class), projectDir))
                .id("release-build");
        binder.bind(TaskHandler.class)
                .to(c -> new ReleaseCommand.Verify(c.get(ArtifactStore.class), projectDir))
                .id("release-verify");
        binder.bind(TaskHandler.class)
                .to(c -> new ReleaseCommand.Test(c.get(ArtifactStore.class), projectDir))
                .id("release-test");
        binder.bind(TaskHandler.class)
                .to(c -> new ReleaseCommand.Dist(c.get(ArtifactStore.class), projectDir))
                .id("release-dist");
        binder.bind(TaskHandler.class)
                .to(c -> new ReleaseCommand.Publish(projectDir))
                .id("release-publish");
    }
}
