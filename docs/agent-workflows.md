# Agent workflows

jeno is designed so an agent can discover capabilities first, then run
non-interactive commands and parse one stable document per invocation.

## Discovery

```bash
jeno --json --help
jeno --json verify --help
jeno --json --version
```

The catalog states, per command:

- `supportsJson`
- `network`: `never | optional | always`
- `writes`: project/target/cache/sources/locks/user-install/remote
- `workspace`: `global | module | aggregate`
- `supportsDryRun`, `destructive`, `confirmation`
- `passThrough`, `interactive`

Do not infer write or network behavior from the command name.

## Result rules

- JSON-capable command: stdout is exactly one JSON document.
- Exit `0`: `status=ok`.
- Exit `1`: `status=failed` (tests failed, lock stale, vulnerabilities, verify failed).
- Exit `2`: `status=error` (usage, safety refusal, unsupported mode).
- Diagnostics carry stable `code`, human `message`, and argv `nextActions`.
- Human progress and test process logs go to stderr.
- `jeno test` additionally writes JUnit XML reports under
  `target/test-results/`.
- `run` is pass-through: stdout/stderr/exit code belong to the program.

## Library golden path

```bash
jeno --json init
jeno --json add "org.slf4j:slf4j-api" 2.0.18
jeno --json lock --check
jeno --json build
jeno --json test                 # read result.reportFiles as JUnit XML
jeno --json package
jeno --json verify
jeno --json publish --dry-run local:../repo
jeno --json publish local:../repo
```

On test failure, inspect the XML listed in each module's
`result.reportFiles`; stdout still remains one JSON document.

## Application golden path

```bash
jeno --json build
jeno --json test
jeno --json bundle
jeno --json verify
jeno --json release --dry-run --allow-unsigned local:../releases
jeno --json release --allow-unsigned local:../releases
```

Use `result.artifacts` from `bundle` for the directory and tar.gz paths and
hashes. `release` reports the installer URL, generated files, signing mode, and
the URLs it uploaded.

## Workspace

At a workspace root, aggregate commands emit one document with deterministic
`modules[]` order and a `summary`:

```bash
jeno --json doctor
jeno --json verify
jeno --json build
jeno --json package
jeno --json bundle
jeno --json clean --dist
```

Module-scoped commands require `--module`:

```bash
jeno --json add "com.example:lib" 1.0.0 --module apps/web
jeno --json remove "com.example:lib" --module apps/web
jeno --json info "com.example:lib" --module apps/web
jeno --json publish --dry-run "local:../repo" --module libs/core
```

## Cross-repository native dependencies

A descriptor may carry an absolute native source:

```toml
"freeway-commons" = "https://dzb.github.io/freeway/@2.0.2"
```

The final `@version` is the semver selector; the URL before it is the jeno
repository base. A bare version remains a sibling of the descriptor's actual
repository. This lets a library published in one jeno repository depend on an
artifact in another without copying the other artifact into the same vendor
repository.

## API edge inspection

`jeno tree --json` exposes both `children` and `apiChildren`; `jeno why --json`
exposes each path's `pathEdges` (`{from,to,api}`). The plain `children`/`paths`
fields stay stable for existing consumers. Human output marks API edges as
`api <child>` in tree and `--api->` in why.

## Safety

- `cache purge` without `--yes`: exit 2, `CONFIRMATION_REQUIRED`, nothing deleted.
- `publish --dry-run` and `release --dry-run` do not write target/dist, sign,
  create a remote target, or contact the network.
- `--offline` permits local/no-target publishing and rejects `gh:` / remote
  jeno targets with `OFFLINE_FORBIDDEN` before any network request.
- Plain HTTP is accepted only for literal private-network addresses
  (`192.168/10/172.16`, loopback, IPv6 ULA/link-local). Non-loopback HTTP emits
  a `PLAIN_HTTP_INSECURE` warning; public hosts require HTTPS. A
  `JENO_PUBLISH_TOKEN` is never sent over cleartext LAN unless the named
  jeno repository declares `insecure = true`, or the process-wide
  `JENO_ALLOW_INSECURE_TOKEN=1` escape hatch is explicitly set. Prefer the
  repository-local opt-in; `jeno publish mine` / `jeno release mine` resolve a
  declared `[repos.mine]` target with its policy.
- `add --json <name> --repo <repo>` is named-repository discovery and returns
  versions without changing the project.
- In structured `[repos.<name>]` tables, omitted `type` means the native jeno
  layout. Maven repositories must say `type = "maven"`. Old string repository
  declarations were removed.
- Current locks are v3: jeno sources are bare URLs/`local:` paths and Maven
  sources are `maven:...`. Old lock versions are read-only migration input;
  `jeno lock` rewrites them to v3, whose text never contains `jeno:`.
- A no-value `add --json` without `--repo` returns `JSON_DISCOVERY_UNSUPPORTED`;
  pass an explicit version for mutation.

## Release checks in a workspace

Use `--locked` to make the lock and store the only byte source:

```bash
jeno --locked verify
jeno --locked build
jeno --locked package
```

This disables local `target/classes` substitution for the whole invocation. It
does not change `jeno.lock`; a missing store artifact is a failure instead of a
silent fallback to an unbuilt/working-tree module.

## CI pattern

```bash
jeno --json lock --check
jeno --json build
jeno --json test
jeno --json verify
jeno --json package
```

Treat JUnit XML as the test-report artifact and jeno JSON as the orchestration
contract. Never combine both on stdout.
