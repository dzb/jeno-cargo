# Agent workflows

mjm is designed so an agent can discover capabilities first, then run
non-interactive commands and parse one stable document per invocation.

## Discovery

```bash
mjm --json --help
mjm --json verify --help
mjm --json --version
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
- `mjm test` additionally writes JUnit XML reports under
  `target/test-results/`.
- `run` is pass-through: stdout/stderr/exit code belong to the program.

## Library golden path

```bash
mjm --json init
mjm --json add "org.slf4j:slf4j-api" 2.0.18
mjm --json lock --check
mjm --json build
mjm --json test                 # read result.reportFiles as JUnit XML
mjm --json package
mjm --json verify
mjm --json publish --dry-run mjm:local:../repo
mjm --json publish mjm:local:../repo
```

On test failure, inspect the XML listed in each module's
`result.reportFiles`; stdout still remains one JSON document.

## Application golden path

```bash
mjm --json build
mjm --json test
mjm --json bundle
mjm --json verify
mjm --json release --dry-run --allow-unsigned mjm:local:../releases
mjm --json release --allow-unsigned mjm:local:../releases
```

Use `result.artifacts` from `bundle` for the directory and tar.gz paths and
hashes. `release` reports the installer URL, generated files, signing mode, and
the URLs it uploaded.

## Workspace

At a workspace root, aggregate commands emit one document with deterministic
`modules[]` order and a `summary`:

```bash
mjm --json doctor
mjm --json verify
mjm --json build
mjm --json package
mjm --json bundle
mjm --json clean --dist
```

Module-scoped commands require `--module`:

```bash
mjm --json add "com.example:lib" 1.0.0 --module apps/web
mjm --json remove "com.example:lib" --module apps/web
mjm --json info "com.example:lib" --module apps/web
mjm --json publish --dry-run "mjm:local:../repo" --module libs/core
```

## Safety

- `cache purge` without `--yes`: exit 2, `CONFIRMATION_REQUIRED`, nothing deleted.
- `publish --dry-run` and `release --dry-run` do not write target/dist, sign,
  create a remote target, or contact the network.
- `--offline` permits local/no-target publishing and rejects `gh:` / remote
  mjm targets with `OFFLINE_FORBIDDEN` before any network request.
- Plain HTTP is accepted only for literal private-network addresses
  (`192.168/10/172.16`, loopback, IPv6 ULA/link-local). Non-loopback HTTP emits
  a `PLAIN_HTTP_INSECURE` warning; public hosts require HTTPS.
- `add --json` without a value is remote discovery and returns
  `JSON_DISCOVERY_UNSUPPORTED`; pass an explicit version for mutation.

## CI pattern

```bash
mjm --json lock --check
mjm --json build
mjm --json test
mjm --json verify
mjm --json package
```

Treat JUnit XML as the test-report artifact and mjm JSON as the orchestration
contract. Never combine both on stdout.
