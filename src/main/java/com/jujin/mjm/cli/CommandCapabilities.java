package com.jujin.mjm.cli;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Machine-discoverable capabilities of one command.
 *
 * <p>This is deliberately explicit rather than inferred from names or flags:
 * an agent must be able to answer "does this command write project files?",
 * "can it reach the network?", "does it aggregate a workspace?", and "is there
 * a dry-run mode?" before invoking anything with side effects.
 */
public record CommandCapabilities(
        boolean supportsJson,
        NetworkMode network,
        Set<WriteScope> writes,
        WorkspaceMode workspace,
        boolean supportsDryRun,
        boolean destructive,
        Confirmation confirmation,
        boolean passThrough,
        boolean interactive) {

    public CommandCapabilities {
        writes = writes.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(writes));
    }

    public enum NetworkMode {
        /** Never contacts a repository, registry, or update service. */
        NEVER,
        /** May contact the network depending on arguments or a cold store. */
        OPTIONAL,
        /** Always requires network access, even when all inputs are cached. */
        ALWAYS
    }

    public enum WriteScope {
        PROJECT,
        TARGET,
        CACHE,
        SOURCES,
        LOCKS,
        USER_INSTALL,
        REMOTE
    }

    public enum WorkspaceMode {
        /** Not tied to one module; may operate outside a workspace. */
        GLOBAL,
        /** Operates on exactly one module, selected with {@code --module}. */
        MODULE,
        /** At a workspace root, aggregates every module in dependency order. */
        AGGREGATE
    }

    public enum Confirmation {
        NONE,
        /** Some invocations (for example destructive subcommands) need --yes. */
        CONDITIONAL,
        REQUIRES_YES,
        REQUIRES_SIGNATURE
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Small fluent builder so each command's declaration stays readable. */
    public static final class Builder {
        private boolean supportsJson;
        private NetworkMode network = NetworkMode.NEVER;
        private final Set<WriteScope> writes = EnumSet.noneOf(WriteScope.class);
        private WorkspaceMode workspace = WorkspaceMode.MODULE;
        private boolean supportsDryRun;
        private boolean destructive;
        private Confirmation confirmation = Confirmation.NONE;
        private boolean passThrough;
        private boolean interactive;

        private Builder() {}

        public Builder json() {
            this.supportsJson = true;
            return this;
        }

        public Builder network(NetworkMode network) {
            this.network = network;
            return this;
        }

        public Builder writes(WriteScope... scopes) {
            Collections.addAll(writes, scopes);
            return this;
        }

        public Builder workspace(WorkspaceMode workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder dryRun() {
            this.supportsDryRun = true;
            return this;
        }

        public Builder destructive() {
            this.destructive = true;
            return this;
        }

        public Builder confirmation(Confirmation confirmation) {
            this.confirmation = confirmation;
            return this;
        }

        public Builder passThrough() {
            this.passThrough = true;
            return this;
        }

        public Builder interactive() {
            this.interactive = true;
            return this;
        }

        public CommandCapabilities build() {
            return new CommandCapabilities(supportsJson, network, writes, workspace,
                    supportsDryRun, destructive, confirmation, passThrough, interactive);
        }
    }
}
