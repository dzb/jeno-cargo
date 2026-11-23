## mjm — modern java manager

[![Latest release](https://img.shields.io/github/v/release/dzb/mjm-cargo?label=release)](https://github.com/dzb/mjm-cargo/releases/latest) [![JDK](https://img.shields.io/badge/JDK-25%2B-orange)](https://jdk.java.net/25/) ![runtime deps](https://img.shields.io/badge/runtime%20deps-0-brightgreen)

A package manager and build tool for Java, built to replace Maven for the
Freeway ecosystem — and then to outgrow that role.

mjm is **self-hosting**: the previous release builds the next one. Every
release you download was compiled, tested, packaged and published by the
release before it. That is not a slogan; it is the only way mjm is ever built.

```bash
curl -fsSL https://raw.githubusercontent.com/dzb/mjm-cargo/master/setup.sh | bash
mjm --version          # mjm <version> — modern java manager
```

**Docs** · [DESIGN.md](DESIGN.md) (ADR summary D1–D16, every decision traceable) · [SPEC.md](SPEC.md) (the contract: files, CLI, artifacts) · [RELEASE.md](RELEASE.md) (release runbook) · [REMINDS.md](REMINDS.md) (deliberate tradeoffs) · [docs/history-0.2.md](docs/history-0.2.md) (why the 0.2 line looks the way it does) · [LESSONS.md](LESSONS.md) (what each incident taught)

---

## Why not Maven

Maven solved dependency management for a generation, and its model has three
costs mjm refuses to inherit:

| Maven | mjm |
|---|---|
| XML descriptors, mutable and inheritance-heavy | TOML, closed schema, no property interpolation |
| Central is *the* registry | any URL that serves bytes is a registry |
| plugins and profiles, unbounded configuration | zero plugins; `[scripts]` and build flags |
| version ranges and silent conflict resolution | exact versions; conflicts are errors with paths |
| transitive dependencies quietly on the compile path | **strict visibility**: only declared deps compile |

The last one is the core.**mjm compiles against exactly what you declared.**
Anything transitive is runtime-only. If you import a class you did not
declare, the build fails — by construction, not by convention. That one rule
eliminates the entire class of `NoSuchMethodError` archaeology.

## The three ideas

### 1. Semantic-aware MVS, not version ranges

Every dependency is pinned to an exact version. When two paths demand the same
artifact, mjm resolves per id:

- **same major** → take the max (Maven's ordering)
- **different majors** → **hard conflict**, with both introduction paths named
- non-semver versions (calendar versions, `30.0-jre`) → plain max-wins

The reasoning: silently taking the max across a major version boundary is
exactly how a program compiles cleanly and dies at runtime. mjm refuses to be
an accomplice. The exits are named: align the versions, or pin one in
`[overrides]`.

### 2. A depth budget

`[limits].max-depth` (default **2**) bounds the longest introduction chain.
A library at depth 5 with 40 transitive jars is not a dependency, it is an
entanglement. The budget is a hard error — no exemption mechanism, because
exemptions are how budgets die.

The intended answer to a breach is structural: swap the library for a smaller
one, exclude a subtree, or consciously raise the budget. `mjm tree` renders the
depths; `mjm why <id>` shows every path that introduces an artifact.

### 3. Two scopes, one graph

`[dev-deps]` is the test-only group (Cargo's `dev-dependencies`, uv's
dependency groups). It resolves into the same `mjm.lock`, but never reaches the
compile classpath, `mjm run`, `mjm package`, `mjm bundle`, or a **published
descriptor**. Production use wins: an artifact reachable from both scopes is
main.

This is what makes mjm's own toolchain honest — junit is a dev dependency, so it
can never leak into what mjm publishes.

## Quick start

```bash
mjm init                      # skeleton mjm.toml
mjm add "org.slf4j:slf4j-api" 2.0.18
mjm lock                      # resolve, download into ${XDG_CACHE_HOME:-~/.cache}/mjm/store, pin sha256
mjm build                     # javac against declared deps only (resources-only modules copy too)
mjm test                      # forked JVM: @MjmTest + JUnit Jupiter
mjm run com.example.App
mjm cache purge --dry-run     # destructive: shows store/sources; --yes actually deletes
```

### Migrating from Maven

```bash
cd your-maven-project
mjm init --pom                # translate pom.xml into mjm.toml
```

The translation is a **reviewed migration, not an import**: properties are
resolved into literal versions, `<scope>test</scope>` lands in `[dev-deps]`,
`dependencyManagement` supplies missing versions, and anything mjm cannot
express — snapshots, classifiers, `system`/`import` scopes, unresolved
properties — is **printed as a note instead of silently dropped**. The
generated file is validated before it is written.

### Sharing a library

```bash
mjm publish gh:acme/widget
# → released acme/widget@v1.2.0 (3 assets)
#   share it with:
#     mjm add "widget" gh:acme/widget@v1.2.0
```

Consumers get the facts, not a README:

```bash
$ mjm info "acme:widget"
acme:widget@1.2.0
  A tiny reactive widget toolkit
  homepage: https://github.com/acme/widget
  license:  Apache-2.0
  java:     21+
  deps:     org.slf4j:slf4j-api@2.0.18
  origin:   gh:acme/widget
  source:   https://github.com/acme/widget/tree/v1.2.0

fetch the tree: mjm info "acme:widget" --source
```

`--source` unpacks the source tree at the **exact tag the lockfile pins**. mjm
never publishes a `-sources.jar`: Go and Cargo never split source from
artifact, and a sources jar is a patch for Java's binary-first distribution,
not a capability. What matters is that source is *addressable* — and the
lockfile already carries the tag. (Central dependencies, published by a
binary-first ecosystem, still fall back to the publisher's own sources jar.)

## Publishing: no registry

There is no mjm registry, and no center it knows about. **A repository is a URL.**

Publishing is uploading three files where a URL can reach them:

```
mylib-1.2.0.jar
mylib-1.2.0.mjm.toml     ← the descriptor: its own deps, java requirement, identity
mylib-1.2.0.sha256
```

The layout is [SPEC.md](SPEC.md) §7; a Maven-layout target is refused because mjm writes no POM.

| Where | Consumed as | How it is published |
|---|---|---|
| GitHub Releases | `gh:owner/repo@v1.2.3` | `mjm publish gh:owner/repo` |
| ~~Gitee Releases~~ | 已移除:附件匿名下载需令牌,违反零配置;用裸 URL 或自建主机 | — |
| **your own host** — mjm layout | `[repos.mine]` `type = "mjm"`, `url = "https://my.host/"` + `"lib" = "mine:1.2.3"` | `mjm publish mjm:https://my.host/` — two levels, no `group` needed |
| **your own host** — Maven layout (the default type) | `[repos.corp]` `url = "https://nexus.corp/"` + `"com.corp:lib" = "corp:1.2.3"` | readable by mjm, but **not a publish target**: mjm writes no POM. Publish the layout with your own tooling, or use the mjm layout |
| a local directory | `[repos.vend]` `type = "mjm"`, `url = "vendor"` (or the legacy `local:/path` string) | `mjm publish mjm:local:vendor` (writes the triple in place) |
| any static server | a bare `https://…/x.jar` leaf | upload the jar your way (a leaf carries no descriptor, so it is not a `publish` target) |

The `mjm:` layout is the shortest one: `mjm publish mjm:https://my.host/` writes
`<name>/<version>/<name>-<version>.{jar,mjm.toml,sha256}` and needs no `[project].group`
on either side. A Maven-layout target is **refused**: mjm generates no POM, so
neither mjm nor Maven could read the triple back. Maven repositories remain
readable (`[repos] type = "maven"`); publishing into one needs a POM generator,
which mjm does not have. A dependency names its repository in the value
(`corp:1.2.3`); the older `[deps.corp]` table form is legacy but still read.
`gh` is a reserved name — the `gh:` prefix wins, so a repository cannot take it.
`MJM_PUBLISH_TOKEN` adds a bearer token when the mjm-layout host wants one.

Publishing accepts only targets mjm can read back — a GitHub release, an mjm
layout over HTTPS, or an mjm layout on disk. GitHub gets native upload because
its release API is stable and public; everything else is the three files over a
plain PUT, and nothing else is promised.

The descriptor travels with the jar, so a published library is a **full
citizen**: its dependencies participate in the consumer's closure and depth
budget exactly like a direct dependency. A bare jar with no descriptor is a
leaf.

Because a repository is just a URL, moving a library — from GitHub to your own
server, from a company registry to a mirror — changes one line in `[repos]`. No
account migration, no re-publishing under a new namespace.

Determinism does not depend on the host. `mjm.lock` pins **two** digests per
artifact — the bytes and the metadata. A release asset can be deleted,
replaced, or served from a different CDN; the lockfile still defines what is
correct, and a mismatch is a hard failure.

## Self-hosting

mjm builds mjm, and freeway (the framework mjm is built on) is built by mjm too.

```
        ┌──────────────────────────────────────────────┐
        │  v(n) compiles → tests → packages → publishes v(n+1)
        └──────────────────────────────────────────────┘
```

The lifecycle is explicit steps, and that is deliberate: **mjm has no `release`
command.** Deciding when to build, what to name it, and where to upload it is a
project's CI job; mjm provides the mechanics one step at a time.

```bash
mjm lock && mjm build && mjm test   # verify the code under test
mjm bundle                          # assemble the installable distribution
gh release create vX.Y.Z ...        # the CI decides to publish
```

**Bootstrap discipline** (the part that actually matters). CI compiles the tree
with `javac` against `vendor/`, then every later step — lock, build, test,
bundle — runs that freshly compiled `mjm`, not a previously installed one. The
artifact must reflect the code under test: a change to the build itself
(generating the version resource, a new javac flag) cannot be executed by a
tool that predates it, and running the old tool silently makes every such
change land one release late. (Recorded in [REMINDS.md](REMINDS.md) §2.)

The one compatibility question a previous release *could* answer — "does the
shipped `mjm` still read this `mjm.toml`?" — is answered by the release
checklist rather than by CI: the format is a closed schema and the reader is
versioned with it.

Version numbers are declared **once**, in `mjm.toml`. The build derives them into
`META-INF/mjm/version.properties` and the jar manifest; the running binary reads
the resource. No version string is hand-maintained in source.

## The distribution

```
mjm-<version>/
├── bin/mjm                       launcher
├── lib/
│   ├── mjm-<version>.jar         mjm + freeway + slf4j, one uber-jar
│   └── toolchain/               junit — loaded only by the forked test JVM
├── setup.sh
└── SHA256SUMS
```

One jar. freeway is mjm's skeleton, not a dependency a user could swap — so it
is merged in, not shipped beside. The test toolchain is separate because a
production run never loads a single byte of it.

`mjm bundle` assembles that directory and a `tar.gz` for `curl | bash`; `setup.sh` installs
surgically: it replaces `lib/` and `bin/`, and leaves `${XDG_CACHE_HOME:-~/.cache}/mjm/store` (your entire
dependency cache) untouched. `mjm upgrade` (alias `mjm update`) upgrades in place and takes effect on
the next invocation.

## Commands

A few everyday shapes; `mjm --help` prints the command list, and
[SPEC.md](SPEC.md) §6 is the contract (arguments, exit codes, workspace
strategy, JSON fields).

```sh
mjm init
mjm add "org.apache.logging.log4j:log4j-api" 2.25.4
mjm lock
mjm build && mjm test
mjm run                          # [project].main, or: mjm run app.Main -- args…
mjm package --uber
mjm bundle
mjm classpath --test
mjm audit
mjm publish gh:owner/repo
```

Unknown arguments are usage errors (exit 2), never silently ignored. `--` ends
mjm's own options: after it, arguments belong to the command — for `run`, to the
program (`mjm run app.Main -- --offline` passes `--offline` through).

Deliverables moved: `dist/` is now `target/dist/` (it is build output, and
`mjm clean` removes the whole `target/` tree). Scripts that read `dist/` need
one edit; an existing `dist/` is no longer read and `publish` tells you so.


### Local workspaces

`mjm.ws.toml` is a local file — do not commit it. `mjm init --ws` writes it from
the module directories it finds (or from explicit paths); `[ws] modules = [...]`
lists them. Inside a workspace, a lock row whose id is one of those modules
resolves to that module's `target/classes` instead of the store jar, and a root
directory runs `lock` / `build` / `test` / `package` / `bundle` / `clean` once
per module in dependency order (`--module <path>` narrows it to one). A root
refuses `mjm init` / `init --pom` (it must not become a project), and commands
that act on one project — `add`, `remove`, `run`, `info`, `tree`, `why`,
`classpath`, `publish`, `audit`, `outdated` — require `--module` there.

The substitution happens where the classpath is assembled, so it is visible to
`mjm build`, `mjm test`, `mjm run` and `mjm classpath`; the lockfile itself is
unchanged. The file format is [SPEC.md](SPEC.md) §5; the design record, including
why `publish` is unaffected, is in [docs/workspace-design.md](docs/workspace-design.md).

## Where the truth lives

| File | Role |
|---|---|
| [SPEC.md](SPEC.md) | the contract: file formats, CLI, artifact layout; wins on conflict |
| [DESIGN.md](DESIGN.md) | the reasons: decisions, alternatives, architecture, budget |
| [RELEASE.md](RELEASE.md) | how mjm itself is released (pipeline details) |
| [REMINDS.md](REMINDS.md) | deliberate tradeoffs, with cost and re-decision trigger |
| [docs/comparison.md](docs/comparison.md) | historical survey of other package managers |
| [docs/history-0.2.md](docs/history-0.2.md) | release-by-release history |

## Design notes

mjm is built on four rules, visible throughout
[DESIGN.md](DESIGN.md):

- **Zero runtime dependencies** beyond the Freeway framework it is built on —
  which itself has none. No plugin system, no bytecode weaving, no scanning.
- **Closed schemas.** `mjm.toml`, the descriptor and `mjm.lock` each accept
  exactly one shape. A misspelled `[dep]` is an error, never a silently empty
  table. Unknown keys fail.
- **Every fact has one owner.** The lockfile owns bytes; `mjm.toml` owns rules;
  the descriptor owns published facts. Nothing is duplicated and nothing is
  guessed — including the version, which exists in source exactly zero times.
- **Next-action errors.** Resolver, decoder and store speak in what to do next
  ("run mjm lock", "align the versions, or pin one in [overrides]"), not in
  stack traces.

[SPEC.md](SPEC.md) is the formal contract: file grammars, CLI behaviour, artifact
layout and the Maven decoding scope. [REMINDS.md](REMINDS.md) records
the deliberate tradeoffs — each with its cost and the condition that should
make someone re-decide.

## Status

**Self-hosting end to end.** mjm builds, tests, packages, publishes and
upgrades itself, and builds every freeway module in CI. The test suite is
green on every push that touches code — the badge above tracks the release.

Not yet done, and known: JUnit XML reports for CI tooling, multi-module
(workspace) builds. A discovery/index layer is deliberately deferred until
there is an ecosystem worth indexing — knowing a package's coordinates has to
work first.

## License

Apache 2.0.
