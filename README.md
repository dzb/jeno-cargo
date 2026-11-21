## mj — modern java manager

[![Version](https://img.shields.io/badge/version-0.1.4-blue)](https://github.com/dzb/mj-cargo/releases) [![JDK](https://img.shields.io/badge/JDK-25%2B-orange)](https://jdk.java.net/25/) ![runtime deps](https://img.shields.io/badge/runtime%20deps-0-brightgreen) ![tests](https://img.shields.io/badge/tests-145%20passing-brightgreen)

A package manager and build tool for Java, built to replace Maven for the
Freeway ecosystem — and then to outgrow that role.

mj is **self-hosting**: the previous release builds the next one. Every
release you download was compiled, tested, packaged and published by the
release before it. That is not a slogan; it is the only way mj is ever built.

```bash
curl -fsSL https://raw.githubusercontent.com/dzb/mj-cargo/master/setup.sh | bash
mj --version          # mj 0.1.4 — modern java manager
```

**Docs** · [DESIGN.md](DESIGN.md) (14 ADRs, every decision traceable) · [SPEC.md](SPEC.md) (closed schemas) · [RELEASE.md](RELEASE.md) (release runbook) · [REMINDS.md](REMINDS.md) (deliberate tradeoffs)

---

## Why not Maven

Maven solved dependency management for a generation, and its model has three
costs mj refuses to inherit:

| Maven | mj |
|---|---|
| XML descriptors, mutable and inheritance-heavy | TOML, closed schema, no property interpolation |
| Central is *the* registry | any URL that serves bytes is a registry |
| plugins and profiles, unbounded configuration | zero plugins; `[scripts]` and build flags |
| version ranges and silent conflict resolution | exact versions; conflicts are errors with paths |
| transitive dependencies quietly on the compile path | **strict visibility**: only declared deps compile |

The last one is the core.**mj compiles against exactly what you declared.**
Anything transitive is runtime-only. If you import a class you did not
declare, the build fails — by construction, not by convention. That one rule
eliminates the entire class of `NoSuchMethodError` archaeology.

## The three ideas

### 1. Semantic-aware MVS, not version ranges

Every dependency is pinned to an exact version. When two paths demand the same
artifact, mj resolves per id:

- **same major** → take the max (Maven's ordering)
- **different majors** → **hard conflict**, with both introduction paths named
- non-semver versions (calendar versions, `30.0-jre`) → plain max-wins

The reasoning: silently taking the max across a major version boundary is
exactly how a program compiles cleanly and dies at runtime. mj refuses to be
an accomplice. The exits are named: align the versions, or pin one in
`[overrides]`.

### 2. A depth budget

`[limits].max-depth` (default **2**) bounds the longest introduction chain.
A library at depth 5 with 40 transitive jars is not a dependency, it is an
entanglement. The budget is a hard error — no exemption mechanism, because
exemptions are how budgets die.

The intended answer to a breach is structural: swap the library for a smaller
one, exclude a subtree, or consciously raise the budget. `mj tree` renders the
depths; `mj why <id>` shows every path that introduces an artifact.

### 3. Two scopes, one graph

`[dev-deps]` is the test-only group (Cargo's `dev-dependencies`, uv's
dependency groups). It resolves into the same `mj.lock`, but never reaches the
compile classpath, `mj run`, `mj package`, `mj bundle`, or a **published
descriptor**. Production use wins: an artifact reachable from both scopes is
main.

This is what makes mj's own toolchain honest — junit is a dev dependency, so it
can never leak into what mj publishes.

## Quick start

```bash
mj init                      # skeleton mj.toml
mj add "org.slf4j:slf4j-api" 2.0.18
mj lock                      # resolve, download into ~/.mj/store, pin sha256
mj build                     # javac against declared deps only
mj test                      # forked JVM: @MjTest + JUnit Jupiter
mj run com.example.App
```

### Migrating from Maven

```bash
cd your-maven-project
mj init --pom                # translate pom.xml into mj.toml
```

The translation is a **reviewed migration, not an import**: properties are
resolved into literal versions, `<scope>test</scope>` lands in `[dev-deps]`,
`dependencyManagement` supplies missing versions, and anything mj cannot
express — snapshots, classifiers, `system`/`import` scopes, unresolved
properties — is **printed as a note instead of silently dropped**. The
generated file is validated before it is written.

### Sharing a library

```bash
mj publish gh:acme/widget
# → released acme/widget@v1.2.0 (3 assets)
#   share it with:
#     mj add "widget" gh:acme/widget@v1.2.0
```

Consumers get the facts, not a README:

```bash
$ mj info "acme:widget"
acme:widget@1.2.0
  A tiny reactive widget toolkit
  homepage: https://github.com/acme/widget
  license:  Apache-2.0
  java:     21+
  deps:     org.slf4j:slf4j-api@2.0.18
  origin:   gh:acme/widget
  source:   https://github.com/acme/widget/tree/v1.2.0

fetch the tree: mj info "acme:widget" --source
```

`--source` unpacks the source tree at the **exact tag the lockfile pins**. mj
never publishes a `-sources.jar`: Go and Cargo never split source from
artifact, and a sources jar is a patch for Java's binary-first distribution,
not a capability. What matters is that source is *addressable* — and the
lockfile already carries the tag. (Central dependencies, published by a
binary-first ecosystem, still fall back to the publisher's own sources jar.)

## Publishing: no registry

There is no mj registry. Publishing is uploading three files where a URL can
reach them:

```
mylib-1.2.0.jar
mylib-1.2.0.mj.toml     ← the descriptor: its own deps, java requirement, identity
mylib-1.2.0.sha256
```

- `gh:owner/repo@v1.2.3` → GitHub Releases
- `gitee:owner/repo@v1.2.3` → Gitee Releases
- `https://…/x.jar` → any static server, a bare leaf
- `local:/path` → a Maven-layout directory (bootstrap and air-gapped builds)

The descriptor travels with the jar, so a published library is a **full
citizen**: its dependencies participate in the consumer's closure and depth
budget exactly like a direct dependency. A bare jar with no descriptor is a
leaf.

Determinism does not depend on the host. `mj.lock` pins **two** digests per
artifact — the bytes and the metadata. A release asset can be deleted,
replaced, or served from a different CDN; the lockfile still defines what is
correct, and a mismatch is a hard failure.

## Self-hosting

mj builds mj, and freeway (the framework mj is built on) is built by mj too.

```
        ┌──────────────────────────────────────────────┐
        │  v(n) compiles → tests → packages → publishes v(n+1)
        └──────────────────────────────────────────────┘
```

The lifecycle is explicit steps, and that is deliberate: **mj has no `release`
command.** Deciding when to build, what to name it, and where to upload it is a
project's CI job; mj provides the mechanics one step at a time.

```bash
mj lock && mj build && mj test     # verify the code under test
mj bundle                          # assemble the installable distribution
gh release create vX.Y.Z ...       # the CI decides to publish
```

**Bootstrap discipline** (the part that actually matters): CI installs the
*previous* release, then uses it to build the tree under test. That is the
honest test — can the shipped tool consume the current source? But `dist` runs
with the **freshly compiled** classes, because the artifact must reflect the
code under test. Getting this backwards means every change to the package
layout lands one release late, invisibly. (Recorded in
[REMINDS.md](REMINDS.md) §2.)

Version numbers are declared **once**, in `mj.toml`. The build derives them into
`META-INF/mj/version.properties` and the jar manifest; the running binary reads
the resource. No version string is hand-maintained in source.

## The distribution

```
mj-0.1.4/
├── bin/mj                       launcher
├── lib/
│   ├── mj-0.1.4.jar             mj + freeway + slf4j, one uber-jar
│   └── toolchain/               junit — loaded only by the forked test JVM
├── setup.sh
└── SHA256SUMS
```

One jar. freeway is mj's skeleton, not a dependency a user could swap — so it
is merged in, not shipped beside. The test toolchain is separate because a
production run never loads a single byte of it.

`mj bundle` assembles that directory and a `tar.gz` for `curl | bash`; `setup.sh` installs
surgically: it replaces `lib/` and `bin/`, and leaves `~/.mj/store` (your entire
dependency cache) untouched. `mj update` upgrades in place and takes effect on
the next invocation.

## Commands

| | |
|---|---|
| `init` / `init --pom` | create a project, or migrate one from Maven |
| `add` / `remove` | edit a declaration and re-lock |
| `lock` | resolve, download, pin |
| `build` | compile against declared deps only |
| `test` | forked JVM: `@MjTest` + JUnit Jupiter |
| `run` | run on the resolved closure |
| `tree` / `why` | see the graph and every introduction path |
| `info [--source]` | everything about one dependency, including its source |
| `package [--uber]` | the jar — plain, or one merged with its closure |
| `bundle` | an installable distribution (bin + lib + setup.sh + tar.gz) |
| `publish [gh:… \| gitee:…]` | the release triple, with native upload |
| `update` / `upgrade` | self-upgrade from GitHub Releases |

## Design notes

mj is built on four rules, visible throughout
[DESIGN.md](DESIGN.md):

- **Zero runtime dependencies** beyond the Freeway framework it is built on —
  which itself has none. No plugin system, no bytecode weaving, no scanning.
- **Closed schemas.** `mj.toml`, the descriptor and `mj.lock` each accept
  exactly one shape. A misspelled `[dep]` is an error, never a silently empty
  table. Unknown keys fail.
- **Every fact has one owner.** The lockfile owns bytes; `mj.toml` owns rules;
  the descriptor owns published facts. Nothing is duplicated and nothing is
  guessed — including the version, which exists in source exactly zero times.
- **Next-action errors.** Resolver, decoder and store speak in what to do next
  ("run mj lock", "align the versions, or pin one in [overrides]"), not in
  stack traces.

[SPEC.md](SPEC.md) is the formal contract: file grammars, field tables,
validation rules, and the lockfile invariants. [REMINDS.md](REMINDS.md) records
the deliberate tradeoffs — each with its cost and the condition that should
make someone re-decide.

## Status

**v0.1.4 · 145 tests · self-hosting end to end.** mj builds, tests, packages,
publishes and upgrades itself, and builds all seven freeway modules
(2,173 tests across them) in CI.

Not yet done, and known: `mj clean`, `mj outdated`, JUnit XML reports for CI
tooling. A discovery/index layer is deliberately deferred until there is an
ecosystem worth indexing — knowing a package's coordinates has to work first.

## License

Apache 2.0.
