## jeno — modern java manager

[![Latest release](https://img.shields.io/github/v/release/dzb/jeno-cargo?label=release)](https://github.com/dzb/jeno-cargo/releases/latest) [![JDK](https://img.shields.io/badge/JDK-25%2B-orange)](https://jdk.java.net/25/) ![runtime deps](https://img.shields.io/badge/runtime%20deps-0-brightgreen)

A package manager and build tool for Java, built to replace Maven for the
Freeway ecosystem — and then to outgrow that role.

jeno is **self-hosting**: the previous release builds the next one. Every
release you download was compiled, tested, packaged and published by the
release before it. That is not a slogan; it is the only way jeno is ever built.

**Linux/macOS**

```bash
curl -fsSL https://github.com/dzb/jeno-cargo/releases/latest/download/install.sh | bash
jeno --version
```

**Windows** (PowerShell; Windows 10 1803+ provides `tar.exe`)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://github.com/dzb/jeno-cargo/releases/latest/download/install.ps1 | iex"
jeno --version
```

Both installers need JDK 25+. Windows appends `%USERPROFILE%\.jeno\bin` to the
user PATH; `jeno upgrade` verifies sha256 before installing.

**Docs** · [DESIGN.md](DESIGN.md) (ADR summary D1–D23, every decision traceable) · [SPEC.md](SPEC.md) (the contract: files, CLI, artifacts) · [RELEASE.md](RELEASE.md) (release runbook) · [REMINDS.md](REMINDS.md) (deliberate tradeoffs) · [docs/history-0.2.md](docs/history-0.2.md) (why the 0.2 line looks the way it does) · [docs/compat-matrix.md](docs/compat-matrix.md) (real POMs against Central) · [docs/pom-migration.md](docs/pom-migration.md) (init --pom on real projects; [java] + local/Central parents + BOM imports) · [docs/release-e2e.md](docs/release-e2e.md) (a real gh release + clean-HOME install) · [LESSONS.md](LESSONS.md) (what each incident taught)

---

## Why not Maven

Maven solved dependency management for a generation, and its model has three
costs jeno refuses to inherit:

| Maven | jeno |
|---|---|
| XML descriptors, mutable and inheritance-heavy | TOML, closed schema, no property interpolation |
| Central is *the* registry | any URL that serves bytes is a registry |
| plugins and profiles, unbounded configuration | zero plugins; `[scripts]` and build flags |
| version ranges and silent conflict resolution | exact versions; conflicts are errors with paths |
| transitive dependencies quietly on the compile path | **strict visibility**: only declared deps compile |

The last one is the core.**jeno compiles against exactly what you declared.**
Anything transitive is runtime-only. If you import a class you did not
declare, the build fails — by construction, not by convention. That one rule
eliminates the entire class of `NoSuchMethodError` archaeology.

## The three ideas

### 1. Semantic-aware MVS, not version ranges

Every dependency is pinned to an exact version. When two paths demand the same
artifact, jeno resolves per id:

- **same major** → take the max (Maven's ordering)
- **different majors** → **hard conflict**, with both introduction paths named
- non-semver versions (calendar versions, `30.0-jre`) → plain max-wins

The reasoning: silently taking the max across a major version boundary is
exactly how a program compiles cleanly and dies at runtime. jeno refuses to be
an accomplice. The exits are named: align the versions, or pin one in
`[overrides]`.

### 2. A depth budget

`[limits].max-depth` (default **2**) bounds the longest introduction chain.
A library at depth 5 with 40 transitive jars is not a dependency, it is an
entanglement. The budget is a hard error — no exemption mechanism, because
exemptions are how budgets die.

The intended answer to a breach is structural: swap the library for a smaller
one, exclude a subtree, or consciously raise the budget. `jeno tree` renders the
depths; `jeno why <id>` shows every path that introduces an artifact.

### 3. Two scopes, one graph

`[dev-deps]` is the test-only group (Cargo's `dev-dependencies`, uv's
dependency groups). It resolves into the same `jeno.lock`, but never reaches the
compile classpath, `jeno run`, `jeno package`, `jeno bundle`, or a **published
descriptor**. Production use wins: an artifact reachable from both scopes is
main.

In jeno's own manifest JUnit is a main dependency because `JenoTestFork` (part of
the production CLI surface) compiles against the JUnit Platform API. Packaging
metadata in `[toolchain]` marks those same artifacts as test machinery, so
`jeno bundle --toolchain` ships them under `lib/toolchain/` instead of merging
them into the launcher jar.

## Quick start

```bash
jeno init                      # skeleton jeno.toml
jeno add "org.slf4j:slf4j-api" 2.0.18
jeno lock                      # resolve, download into ${XDG_CACHE_HOME:-~/.cache}/jeno/store, pin sha256
jeno build                     # javac against declared deps only (resources-only modules copy too)
jeno test                      # forked JVM: @JenoTest + JUnit Jupiter
jeno run com.example.App
jeno cache purge --dry-run     # destructive: shows store/sources; --yes actually deletes
```

### Migrating from Maven

```bash
cd your-maven-project
jeno init --pom                # translate pom.xml; local/Central parents and BOMs; [java] from Maven properties
```

The translation is a **reviewed migration, not an import**: properties are
resolved into literal versions, `<scope>test</scope>` lands in `[dev-deps]`,
`dependencyManagement` supplies missing versions, and anything jeno cannot
express — snapshots, classifiers, `system`/`import` scopes, unresolved
properties — is **printed as a note instead of silently dropped**. The
generated file is validated before it is written.

### Sharing a library

```bash
jeno publish gh:acme/widget
# → released acme/widget@v1.2.0 (3 assets)
#   share it with:
#     jeno add "widget" gh:acme/widget@v1.2.0
```

Consumers get the facts, not a README:

```bash
$ jeno info "acme:widget"
acme:widget@1.2.0
  A tiny reactive widget toolkit
  homepage: https://github.com/acme/widget
  license:  Apache-2.0
  java:     21+
  deps:     org.slf4j:slf4j-api@2.0.18
  origin:   gh:acme/widget
  source:   https://github.com/acme/widget/tree/v1.2.0

fetch the tree: jeno info "acme:widget" --source
```

`--source` unpacks the source tree at the **exact tag the lockfile pins**. jeno
never publishes a `-sources.jar`: Go and Cargo never split source from
artifact, and a sources jar is a patch for Java's binary-first distribution,
not a capability. What matters is that source is *addressable* — and the
lockfile already carries the tag. (Central dependencies, published by a
binary-first ecosystem, still fall back to the publisher's own sources jar.)

## Publishing: no registry

There is no jeno registry, and no center it knows about. **A repository is a URL.**

Publishing is uploading three files where a URL can reach them:

```
mylib-1.2.0.jar
mylib-1.2.0.jeno.toml     ← the descriptor: its own deps, java requirement, identity
mylib-1.2.0.sha256
```

The layout is [SPEC.md](SPEC.md) §7; a Maven-layout target is refused because jeno writes no POM.

| Where | Consumed as | How it is published |
|---|---|---|
| GitHub Releases | `gh:owner/repo@v1.2.3` | `jeno publish gh:owner/repo` |
| ~~Gitee Releases~~ | 已移除:附件匿名下载需令牌,违反零配置;用裸 URL 或自建主机 | — |
| **your own host** — jeno layout | `[repos.mine]` `type = "jeno"`, `url = "https://my.host/"` (or a literal private-network `http://192.168.x.x/`, with a warning) + `"lib" = "mine:1.2.3"` | `jeno publish jeno:https://my.host/` — two levels, no `group` needed |
| **your own host** — Maven layout (the default type) | `[repos.corp]` `url = "https://nexus.corp/"` + `"com.corp:lib" = "corp:1.2.3"` | readable by jeno, but **not a publish target**: jeno writes no POM. Publish the layout with your own tooling, or use the jeno layout |
| a local directory | `[repos.vend]` `type = "jeno"`, `url = "vendor"` (or the legacy `local:/path` string) | `jeno publish jeno:local:vendor` (writes the triple in place) |
| any static server | a bare `https://…/x.jar` leaf | upload the jar your way (a leaf carries no descriptor, so it is not a `publish` target) |

The `jeno:` layout is the shortest one: `jeno publish jeno:https://my.host/` (or a
literal private-address `jeno:http://192.168.x.x:port/` on a LAN) writes
`<name>/<version>/<name>-<version>.{jar,jeno.toml,sha256}` and needs no `[project].group`
on either side. A Maven-layout target is **refused**: jeno generates no POM, so
neither jeno nor Maven could read the triple back. Maven repositories remain
readable (`[repos] type = "maven"`); publishing into one needs a POM generator,
which jeno does not have. A dependency names its repository in the value
(`corp:1.2.3`); the older `[deps.corp]` table form is legacy but still read.
`gh` is a reserved name — the `gh:` prefix wins, so a repository cannot take it.
`JENO_PUBLISH_TOKEN` adds a bearer token when the jeno-layout host wants one.

Before publishing, run the local preflight:

```bash
jeno verify
```

It checks that every direct declaration still matches `jeno.lock`, that the
lock's jar and metadata bytes are still present and hash-correct in the store,
and that a complete `target/dist` publish triple or bundle `SHA256SUMS` is
internally consistent (a package-only jar is skipped as not prepared; a partial
triple or a bundle directory without `SHA256SUMS` still fails). It never
resolves, downloads or modifies project files.

Publishing accepts only targets jeno can read back — a GitHub release, an jeno
layout over HTTPS, or an jeno layout on disk. GitHub gets native upload because
its release API is stable and public; everything else is the three files over a
plain PUT followed by a GET read-back of every file.

PUT is deliberate, not an implementation accident. The final artifact URLs are
deterministic, PUT is idempotent, and a retry cannot create duplicates or make
the server choose a different name. POST would require a host-specific upload
endpoint, multipart field names and response parsing; that is a narrower,
non-portable contract. WebDAV servers, nginx `dav_module`, Apache `mod_dav` and
object-storage gateways accept PUT. A plain static server accepts neither PUT
nor POST, so it remains read-only. A POST-only host can front the repository
with a PUT-capable gateway.

The descriptor travels with the jar, so a published library is a **full
citizen**: its dependencies participate in the consumer's closure and depth
budget exactly like a direct dependency. A bare jar with no descriptor is a
leaf.

Because a repository is just a URL, moving a library — from GitHub to your own
server, from a company registry to a mirror — changes one line in `[repos]`. No
account migration, no re-publishing under a new namespace.

Determinism does not depend on the host. `jeno.lock` pins **two** digests per
artifact — the bytes and the metadata. A release asset can be deleted,
replaced, or served from a different CDN; the lockfile still defines what is
correct, and a mismatch is a hard failure.

## Self-hosting

jeno builds jeno, and freeway (the framework jeno is built on) is built by jeno too.

```
        ┌──────────────────────────────────────────────┐
        │  v(n) compiles → tests → packages → publishes v(n+1)
        └──────────────────────────────────────────────┘
```

The lifecycle is explicit steps, and that is deliberate: **jeno has no release
lifecycle command.** `jeno release` exists, but it only uploads an installer that
`jeno bundle` already produced — it never builds, tests or bundles implicitly.
Deciding when to run those steps, what to name the release, and where to upload
it remains a project's CI job.

```bash
jeno lock && jeno build && jeno test   # verify the code under test
jeno bundle --toolchain              # assemble jeno's installable distribution
gh release create vX.Y.Z ...        # the CI decides to publish
```

**Bootstrap discipline** (the part that actually matters). CI compiles the tree
with `javac` against `vendor/`, then every later step — lock, build, test,
bundle — runs that freshly compiled `jeno`, not a previously installed one. The
artifact must reflect the code under test: a change to the build itself
(generating the version resource, a new javac flag) cannot be executed by a
tool that predates it, and running the old tool silently makes every such
change land one release late. (Recorded in [REMINDS.md](REMINDS.md) §2.)

The one compatibility question a previous release *could* answer — "does the
shipped `jeno` still read this `jeno.toml`?" — is answered by the release
checklist rather than by CI: the format is a closed schema and the reader is
versioned with it.

Version numbers are declared **once**, in `jeno.toml`. The build derives them into
`META-INF/jeno/version.properties` and the jar manifest; the running binary reads
the resource. No version string is hand-maintained in source.

## The distribution

```
jeno-<version>/
├── bin/jeno                       POSIX launcher
├── bin/jeno.cmd                   Windows launcher
├── lib/
│   ├── jeno-<version>.jar         jeno + freeway + slf4j, one uber-jar
│   └── toolchain/               junit — loaded only by the forked test JVM
├── install.sh
└── SHA256SUMS
```

One jar. freeway is jeno's skeleton, not a dependency a user could swap — so it
is merged in, not shipped beside. The test toolchain is separate because a
production run never loads a single byte of it.

`jeno bundle` assembles that directory and a `tar.gz` for `curl | bash`; it needs
`[project].main` and targets the JDK from `[java].version` (or the build JDK).
`install.sh` verifies `SHA256SUMS` and swaps `lib/` and `bin/` through a staging
directory; `install.ps1` is the Windows equivalent. Both leave
`${XDG_CACHE_HOME:-~/.cache}/jeno/store` (your entire dependency cache) untouched;
`uninstall.sh` removes the install. Artifacts named
in `[toolchain].jars` ship only with `bundle --toolchain`, under
`lib/toolchain/`. `jeno upgrade` (alias `jeno update`) upgrades in place and
takes effect on the next invocation.

jeno's own release tarball is signed: each release carries
`jeno-{version}.tar.gz.minisig`, `install.sh.minisig`, `install.ps1.minisig`
and `minisign.pub`. Verify manually before
installing:

```bash
minisign -V -P "$(tail -1 minisign.pub)" \
  -m jeno-{version}.tar.gz -x jeno-{version}.tar.gz.minisig
```

The key id is documented in [RELEASE.md](RELEASE.md); compare the fingerprint
out of band. `jeno upgrade` and the Windows installer verify sha256 only; they
do not verify the minisign signature (Windows has no minisign dependency).

## Commands

A few everyday shapes; `jeno --help` prints the command list, and
[SPEC.md](SPEC.md) §6 is the contract (arguments, exit codes, workspace
strategy, JSON fields).

```sh
jeno init
jeno add "org.apache.logging.log4j:log4j-api" 2.25.4
jeno lock
jeno build && jeno test
jeno run                          # [project].main, or: jeno run app.Main -- args…
jeno package --uber
jeno bundle
jeno classpath --test
jeno audit
jeno doctor
jeno verify
jeno publish gh:owner/repo

# applications: installer + bootstrap
jeno bundle
jeno release --dry-run                  # show the URLs it would upload
jeno release jeno:local:../releases      # or gh:owner/repo / jeno:https://host/
jeno release jeno:http://192.168.1.39:8080/upload/  # literal private IP only
curl -fsSL https://host/app/install.sh | bash
```

### Signing a release

`jeno release` signs the installer with [minisign](https://jedisct1.github.io/minisign/)
unless you explicitly opt out:

```bash
minisign -G                         # interactive: encrypted key + .pub
minisign -G -W                      # CI/automation: unencrypted key (treat it as a secret)
export JENO_MINISIGN_KEY=~/.minisign/minisign.key
export JENO_MINISIGN_PUBKEY=~/.minisign/minisign.pub   # optional; derived if omitted
jeno bundle
jeno release jeno:https://my.host/
# LAN: jeno release jeno:http://192.168.1.39:8080/upload/   (plain http, private IP only)
```

`JENO_MINISIGN` points at the binary (default `minisign`). Every release carries
`<tar>.minisig`, `install.sh.minisig`, `install.ps1.minisig`, `release.json.minisig` and `minisign.pub`;
the bootstrap embeds the public key, verifies the tar's signature after the
sha256 check, and refuses to install if minisign is missing or the signature is
wrong. Unsigned releases need `--allow-unsigned`, print a warning, and their
bootstrap only checks sha256. A host can still alter the installer and key together:
check the key fingerprint from this README or the release tag out of band — a
signature proves the artifact came from that key, not that the host is honest.

Unknown arguments are usage errors (exit 2), never silently ignored. `--` ends
jeno's own options: after it, arguments belong to the command — for `run`, to the
program (`jeno run app.Main -- --offline` passes `--offline` through).

### Output and colour

Human output adapts to the destination. A terminal gets colour, check marks and
a grouped help page; status lines keep their historical plain text when stdout
is piped, so scripts do not need to parse ANSI escapes. `--json` has the
long-form alias `--format=json`; `--format=text` switches back.

```bash
JENO_COLOR=always jeno --help   # force colour
JENO_COLOR=never  jeno build    # force plain text
NO_COLOR=1       jeno doctor   # the conventional opt-out
```

`JENO_COLOR` accepts `always`, `never`, or the default `auto`. `jeno doctor`,
`jeno tree`, lifecycle status lines and errors use the same palette; completion
lines also include elapsed time on a terminal. Commands that support `--json`
keep stdout as one pure JSON document regardless of the colour setting;
diagnostics still go to stderr.

### Agent command catalog

Machine-discovery starts with one document:

```bash
jeno --json --help
jeno --json verify --help    # one command descriptor
jeno --json --version
```

The catalog is generated from each command's declared capabilities, not a
second hand-maintained list. Every entry states whether JSON output is already
supported, whether the command can use the network, which scopes it writes, its
workspace mode, destructive/confirmation flags, and whether it passes arguments
through to a program:

```json
{
  "schemaVersion": 2,
  "kind": "commandCatalog",
  "commandCount": 22,
  "commands": [
    {
      "name": "verify",
      "supportsJson": true,
      "network": "never",
      "writes": [],
      "workspace": "aggregate",
      "passThrough": false
    }
  ]
}
```

`run` is the one deliberate `supportsJson: false` command: its stdout, stderr,
and exit code belong to the child program. `jeno --json run ...` returns a
structured `JSON_UNSUPPORTED` error; `jeno run app --json` passes `--json` to the
program because everything after the target is the program's.

`jeno info --json` is the consumer-facing artifact export:

```json
{
  "artifact": {
    "id": "acme:widget",
    "group": "acme",
    "artifact": "widget",
    "version": "1.2.0",
    "sha256": "...",
    "metaSha256": "..."
  },
  "provenance": {
    "source": "gh:acme/widget",
    "layout": "github-release",
    "metadata": {"kind": "descriptor", "path": "...", "sha256": "..."},
    "jar": {"path": "...", "local": false}
  },
  "descriptor": {
    "license": "Apache-2.0",
    "javaVersion": 21,
    "dependencies": [{"id": "org.slf4j:slf4j-api", "version": "2.0.18"}]
  },
  "context": {"scope": "main", "depth": 1, "local": false},
  "source": {"page": "...", "archive": "...", "fetched": false}
}
```

This is the node-level contract for downstream tools: hashes and provenance are
artifact facts, while `scope`, `depth`, and local substitution stay under
`context`. `info --source --json` currently returns a structured
`JSON_SOURCE_UNSUPPORTED` error; local metadata export does not write a source
tree.

Artifact-producing commands expose the same module envelope. In single-project
mode `modules` has one entry; at a workspace root it has every module in
dependency order. Each module entry contains an optional `result` object:

```json
{
  "kind": "commandResult",
  "command": "package",
  "status": "ok",
  "modules": [
    {
      "module": ".",
      "status": "ok",
      "result": {
        "artifacts": [
          {
            "kind": "jar",
            "variant": "plain",
            "path": "/abs/project/target/dist/demo-0.1.0.jar",
            "sha256": "...",
            "bytes": 1234
          }
        ]
      }
    }
  ],
  "summary": {"modules": 1, "passedModules": 1, "failedModules": 0}
}
```

`build` reports a `classes` artifact; `package` reports a jar (plain or uber);
`bundle` reports a `bundle-directory` plus the `tar.gz` archive; `clean` reports
the removed paths, byte counts, and file counts.

Mutation commands report the actual project change:

```json
{
  "command": "add",
  "mode": "mutate",
  "dependency": {"id": "com.example:lib", "value": "1.0.0"},
  "manifest": "/abs/project/jeno.toml",
  "lock": "/abs/project/jeno.lock",
  "artifactCount": 1,
  "lockUpdated": true
}
```

`init --json` reports `mode=project|pom|workspace`, the written file, whether it
overwrote an existing file, project identity or workspace module list.
`add --json` without a value is the remote discovery mode and returns a
structured `JSON_DISCOVERY_UNSUPPORTED`; pass an explicit version to get the
mutation contract. `remove --json` reports the removed id and updated lock.
`upgrade --json --check` reports `currentVersion`, `latestVersion`,
`updateAvailable`, `checkOnly`, and never downloads or installs.

`test --json` keeps console test output off stdout and returns the summary plus
the JUnit XML paths:

```json
{
  "command": "test",
  "status": "failed",
  "exitCode": 1,
  "modules": [{
    "module": ".",
    "status": "failed",
    "exitCode": 1,
    "result": {
      "tests": 2,
      "passed": 1,
      "failed": 1,
      "skipped": 0,
      "reportFormat": "junit-xml",
      "reportsDir": "/abs/project/target/test-results",
      "reportFiles": ["/abs/project/target/test-results/TEST-demo.Tests.xml"]
    }
  }],
  "diagnostics": [{"code": "TEST_FAILED"}]
}
```

See [docs/agent-workflows.md](docs/agent-workflows.md) for the complete
discovery and golden-path recipes.

Publishing and releasing have side-effect-free dry-run plans:

```bash
jeno --json publish --dry-run jeno:local:../repo
jeno --json release --dry-run --allow-unsigned jeno:local:../releases
```

`publish --dry-run` reports the existing jar plus the descriptor/checksum it
would write and the URLs it would upload. `release --dry-run` reports the
installer, generated files, signing mode, and would-upload URLs. Neither writes
`target/dist`, creates the remote target, signs a file, or performs a network
request.

`--offline` is target-aware for the publish path: no target and
`jeno:local:` targets are allowed; `gh:` and remote jeno targets return structured
`OFFLINE_FORBIDDEN` before upload. Remote release targets follow the same rule.

The current JSON commands are `tree`, `outdated`, `audit`, `doctor`, `verify`,
`lock` (both normal locking and `--check`), `why`, `classpath`, `info`, `cache`
(including `dir`, `size`, `prune`, and `purge`), `build`, `package`, `bundle`,
`clean`, `init`, `add`, `remove`, `upgrade`, `publish`, `release`, and `test`.
Their root objects all carry:

```json
{
  "schemaVersion": 2,
  "kind": "commandResult",
  "command": "verify",
  "status": "ok",
  "exitCode": 0,
  "dryRun": false,
  "offline": false,
  "diagnostics": []
}
```

`doctor`, `verify`, and `lock --check` also expose a stable `checks[]` or
module report with machine `id`, `status`, and message fields. Workspace-mode
results are one document with a `modules[]` array and a summary, never several
concatenated JSON documents.

Deliverables moved: `dist/` is now `target/dist/` (it is build output, and
`jeno clean` removes the whole `target/` tree). Scripts that read `dist/` need
one edit; an existing `dist/` is no longer read and `publish` tells you so.


### Migrating from old project names

Current jeno only reads `jeno.toml`, `jeno.lock`, and `jeno.ws.toml`. It does **not**
fall back to files from the previous name. Diagnose a checkout with:

```bash
jeno doctor
```

At a workspace root it checks every module; elsewhere it checks the current
project. It reports legacy names, missing manifests/locks, malformed files and
unwritable XDG roots without resolving dependencies or touching the network.
Migration errors name the `mv` command to run, then `jeno lock`.

### Local workspaces

`jeno.ws.toml` is a local file — do not commit it. `jeno init --ws` writes it from
the module directories it finds (or from explicit paths); `[ws] modules = [...]`
lists them. Inside a workspace, a lock row whose id is one of those modules
resolves to that module's `target/classes` instead of the store jar, and a root
directory runs `lock` / `build` / `test` / `package` / `bundle` / `clean` once
per module in dependency order (`--module <path>` narrows it to one). A root
refuses `jeno init` / `init --pom` (it must not become a project), and commands
that act on one project — `add`, `remove`, `run`, `info`, `tree`, `why`,
`classpath`, `publish`, `audit`, `outdated` — require `--module` there.

The substitution happens where the classpath is assembled, so it is visible to
`jeno build`, `jeno test`, `jeno run` and `jeno classpath`; the lockfile itself is
unchanged. The file format is [SPEC.md](SPEC.md) §5; the design record, including
why `publish` is unaffected, is in [docs/workspace-design.md](docs/workspace-design.md).

## Where the truth lives

| File | Role |
|---|---|
| [SPEC.md](SPEC.md) | the contract: file formats, CLI, artifact layout; wins on conflict |
| [DESIGN.md](DESIGN.md) | the reasons: decisions, alternatives, architecture, budget |
| [RELEASE.md](RELEASE.md) | how jeno itself is released (pipeline details) |
| [REMINDS.md](REMINDS.md) | deliberate tradeoffs, with cost and re-decision trigger |
| [docs/comparison.md](docs/comparison.md) | historical survey of other package managers |
| [docs/history-0.2.md](docs/history-0.2.md) | release-by-release history |

## Design notes

jeno is built on four rules, visible throughout
[DESIGN.md](DESIGN.md):

- **Zero runtime dependencies** beyond the Freeway framework it is built on —
  which itself has none. No plugin system, no bytecode weaving, no scanning.
- **Closed schemas.** `jeno.toml`, the descriptor and `jeno.lock` each accept
  exactly one shape. A misspelled `[dep]` is an error, never a silently empty
  table. Unknown keys fail.
- **Every fact has one owner.** The lockfile owns bytes; `jeno.toml` owns rules;
  the descriptor owns published facts. Nothing is duplicated and nothing is
  guessed — including the version, which exists in source exactly zero times.
- **Next-action errors.** Resolver, decoder and store speak in what to do next
  ("run jeno lock", "align the versions, or pin one in [overrides]"), not in
  stack traces.

[SPEC.md](SPEC.md) is the formal contract: file grammars, CLI behaviour, artifact
layout and the Maven decoding scope. [REMINDS.md](REMINDS.md) records
the deliberate tradeoffs — each with its cost and the condition that should
make someone re-decide.

## Status

**Self-hosting end to end.** jeno builds, tests, packages, publishes and
upgrades itself, and builds every freeway module in CI. The test suite is green
on every push that touches code — the badge above tracks the release. JUnit XML
reports and local multi-module workspaces are implemented; the remaining known
gaps are tracked in [REMINDS.md](REMINDS.md) and the release notes. A
discovery/index layer is deliberately deferred until there is an ecosystem
worth indexing — knowing a package's coordinates has to work first.

## License

Apache 2.0.
