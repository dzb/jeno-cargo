package com.jujin.jeno.cli;

import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.Binder;
import com.jujin.jeno.pom.PomSource;
import com.jujin.jeno.project.DescriptorSource;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;

import java.nio.file.Path;

/**
 * mjm's composition (DESIGN.md §9): the store and its three source seams bound
 * once, the commands contributed as extensions against the working directory
 * (the project root), the runner assembled through constructor injection. mjm
 * itself is the flagship Freeway application — the tool that manages
 * dependencies is assembled through dependency injection.
 */
public final class JenoModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        binder.bind(ArtifactStore.class).to(c -> ArtifactStore.userStore());
        binder.bind(PomSource.class).to(StorePomSource.class);
        binder.bind(DescriptorSource.class).to(StoreDescriptorSource.class);
        binder.bind(LockService.class).to(LockService.class);
        binder.bind(CommandRunner.class).to(CommandRunner.class);
        Path projectDir = Path.of("").toAbsolutePath();
        // one run, one project: relative local: bases and relative file: lock
        // rows resolve against this, not against the shell's cwd
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(projectDir);
        binder.contribute(Command.class).addDeferred("lock",
                c -> new LockCommand(c.get(LockService.class), projectDir));
        binder.contribute(Command.class).addDeferred("build",
                c -> new BuildCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("run",
                c -> new RunCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("release",
                c -> new ReleaseCommand(projectDir));
        binder.contribute(Command.class).addDeferred("verify",
                c -> new VerifyCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("publish",
                c -> new PublishCommand(projectDir));
        binder.contribute(Command.class).addDeferred("package",
                c -> new PackageCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("dist",
                c -> new BundleCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("test",
                c -> new TestCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("tree",
                c -> new TreeCommand(c.get(ArtifactStore.class), c.get(PomSource.class),
                        c.get(DescriptorSource.class), projectDir));
        binder.contribute(Command.class).addDeferred("why",
                c -> new WhyCommand(c.get(ArtifactStore.class), c.get(PomSource.class),
                        c.get(DescriptorSource.class), projectDir));
        binder.contribute(Command.class).addDeferred("classpath",
                c -> new ClasspathCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("info",
                c -> new InfoCommand(c.get(ArtifactStore.class), projectDir));
        binder.contribute(Command.class).addDeferred("add",
                c -> new AddCommand(c.get(LockService.class), c.get(ArtifactStore.class),
                        projectDir));
        binder.contribute(Command.class).addDeferred("remove",
                c -> new RemoveCommand(c.get(LockService.class), projectDir));
        binder.contribute(Command.class).addDeferred("init",
                c -> new InitCommand(projectDir, c.get(PomSource.class)));
        binder.contribute(Command.class).addDeferred("clean",
                c -> new CleanCommand(projectDir));
        binder.contribute(Command.class).addDeferred("cache",
                c -> new CacheCommand());
        binder.contribute(Command.class).addDeferred("audit",
                c -> new AuditCommand(projectDir));
        binder.contribute(Command.class).addDeferred("outdated",
                c -> new OutdatedCommand(projectDir));
        binder.contribute(Command.class).addDeferred("doctor",
                c -> new DoctorCommand(projectDir));
        binder.contribute(Command.class).addDeferred("upgrade",
                c -> new UpdateCommand());
    }
}
