# mjm 横向对比:命令语义与目录布局

状态:**历史快照(基于 2026-11-22 的实测与文档调研)**。这份文档记录当时别的工具在同样位置上
做了什么选择,以及哪些选择能让 mjm **变少而不是变多**;它是证据与原则的来源,不是当前能力的清单。
**当前结论**见 [DESIGN.md](../DESIGN.md)(决策与理由)与 [README.md](../README.md)(入口);
功能缺口与排期表(原 DESIGN §13)作为附录保留在这里。

更新:2026-11-22(历史快照);2026-11-23 标注状态并吸收 DESIGN §13 的详细表

---

## 0. 证据来源

这份文档里每条事实都标了来源。两类:

- **[实测]** —— 在本机真跑过命令或真看过目录。命令与结论写在第 0.1 节。
- **[文档]** —— 工具未安装,依据官方文档/长期公开约定。第 0.2 节列出是哪些,以及没验证的部分。

### 0.1 本机实测

| 工具 | 版本/位置 | 跑过什么 | 得到的结论 |
|---|---|---|---|
| mjm | `~/.mjm/bin/mjm` 0.2.4(当时快照) | `mjm`(usage 列表)、`ls target dist`、`du -sh ~/.mjm/*` | 当时:16 个动词;项目内 `target/` + `dist/` 两棵树;全局三处 `~/.mjm/{store,sources,locks}`。**现在**:19 个动词;交付物只在 `target/dist/`;store/sources 在 `${XDG_CACHE_HOME:-~/.cache}/mjm`,锁在 `${XDG_STATE_HOME:-~/.local/state}/mjm`(旧 `~/.mjm` 存在则仍优先) |
| uv | `~/.local/bin/uv` | `uv --help`、`uv cache --help`、`uv python --help`、`uv sync --help`、`uv init` + `uv lock` + `uv sync`(空项目)、`uv cache dir` | 24 个动词;`uv init` 产出 `pyproject.toml`/`uv.lock`/`.python-version`/`src/`/`README`/`.git`;`uv sync` 之后才出现 `.venv`;`uv.lock` 首行 `version = 1` / `revision = 5`;缓存 `~/.cache/uv/{archive-v0,builds-v0,interpreter-v5,sdists-v10,CACHEDIR.TAG}`;`sync` 有 `--locked` / `--frozen` / `--offline` 三个与锁相关的旗标 |
| go | `/usr/bin/go` | `go help`、`go help mod`、`go env GOMODCACHE GOCACHE` | 20 个动词;`mod` 子命令含 `download/vendor/verify/why`;`GOMODCACHE=~/go/pkg/mod`、`GOCACHE=~/.cache/go-build` |
| npm | `/usr/bin/npm` | `npm config get cache` | 缓存 `~/.npm`(非 XDG) |
| maven | `/usr/bin/mvn` | `ls ~/.m2` | `~/.m2/repository` + `settings.xml`;无锁文件 |

mjm 自身的数据(命令面、目录语义)直接读代码与 `mjm` 的 usage 输出,不是回忆。

### 0.2 依据文档、未在本机验证

`cargo`、`pnpm`、`bazel`、`gradle` 四者本机未安装,以下内容来自官方文档与公开约定,
**没有在本机复跑**:

- cargo:`target/{debug,release,package}`、`~/.cargo/registry`、`Cargo.lock`、`[workspace]`、`cargo metadata`
- pnpm:`node_modules/.pnpm` 虚拟存储、全局 store 与硬链、`pnpm-lock.yaml`、`pnpm-workspace.yaml`
- bazel:`MODULE.bazel` + `MODULE.bazel.lock`、项目内 `bazel-*` 软链指向全局 output base、`query --output=json`
- gradle:`build/` + `.gradle/`、`~/.gradle/caches/modules-2`、`gradle.lockfile`(opt-in)、`libs.versions.toml`、toolchains

这几条如果有误,影响的只是「对比表里的格子」,不影响第 5 节的提案——那些提案的论据主要来自
**实测过的 uv/go/mjm 三者**,以及 mjm 自己的代码。

---

## 1. 命令面:动词数量

| 工具 | 动词数 | 来源 |
|---|---|---|
| **mjm** | **19** | 实测(当前;首次实测的 0.2.4 快照为 16) |
| go | 20 | 实测 |
| uv | 24 | 实测 |
| cargo | ~40 | 文档 |
| npm | ~70 | 文档 |

mjm 现有 19 个:`add audit build bundle cache classpath clean info init lock outdated package publish remove run test tree why update`(另有别名 `upgrade`、`dist`)。

**结论:在「动词数量」这条轴上,mjm 不需要再学谁。** 值得记的是 mjm 已经比同级工具更干净的
三处,以及唯一真正缺的那个动词(见 §5 A2)。

### 1.1 mjm 已经更干净的三处

| 做法 | 对比对象 | 为什么更干净 |
|---|---|---|
| `lock` 与 `build` 分开,**build 遇到过期锁直接拒绝** | npm 的 `install` / `ci` 双模式;uv 的 `--locked` / `--frozen` | 「锁是权威」是默认行为,不是模式;没有第二个入口可以绕过 |
| `--offline` 逐命令声明能力(`supportsOffline`),不适用即 exit 2 | uv 的 `--offline` 是全局标志,各命令自行解释 | 「不许假装支持」是代码里的布尔值,不是文档里的期望 |
| 没有「依赖升级」命令 | cargo/npm/uv 都有 | 与「精确钉定 + 双钉」直接冲突;`outdated` 报告 + 人决策是该哲学的结果 |

### 1.2 唯一的缺口:`cache`

除此之外,各工具多出来的动词(mjm 没有的)都属**另一类职责**,不该吸收:

```
go:    bug doc env fix fmt generate install list telemetry tool vet
uv:    auth export format check audit tool python venv pip workspace cache self
npm:   audit cache ci config dedupe diff dist-tag doctor edit exec explain ...
```

其中只有 **`cache`** 是 mjm 真有洞的地方(§5 A2),其余要么是格式化器、要么是漏洞扫描、
要么是历史包袱——都属于「另一件事」。

---

## 2. 目录布局:一条从「零项目输出」到「两棵树」的谱系

| 工具 | manifest | lock | 项目内输出 | 全局缓存 | 可发布产物 |
|---|---|---|---|---|---|
| bazel [文档] | `MODULE.bazel` | `MODULE.bazel.lock` | 只有 `bazel-*` 软链,实体在全局 | `~/.cache/bazel` | — |
| go [实测] | `go.mod` | `go.sum` | 几乎无(二进制写 cwd) | `~/go/pkg/mod`、`~/.cache/go-build` | — |
| pnpm [文档] | `package.json` | `pnpm-lock.yaml` | `node_modules/`(依赖,非产物) | store,硬链入 `node_modules/.pnpm` | `*.tgz` 写 cwd |
| cargo [文档] | `Cargo.toml` | `Cargo.lock` | 一棵树:`target/{debug,release,package}` | `~/.cargo/registry` | `target/package/*.crate`(**树内**) |
| gradle [文档] | `build.gradle(.kts)` | `gradle.lockfile` | 一棵树:`build/`(+ `.gradle/`) | `~/.gradle/caches/modules-2` | `build/libs/*.jar`(**树内**) |
| uv [实测] | `pyproject.toml` | `uv.lock` | `.venv/`;`uv build` 时才出 `dist/` | `~/.cache/uv/{archive-v0,…}` | `dist/*.whl` |
| maven [实测] | `pom.xml` | **无** | `target/` | `~/.m2/repository` | `target/*.jar`(**树内**) |
| **mjm** [实测] | `mjm.toml` | `mjm.lock` | **两棵树**:`target/` + `dist/` | `~/.mjm/{store,sources,locks}` | `dist/`(**树外**) |

### 2.1 事实一:mjm 是唯一把可发布产物默认放到树外的

cargo → `target/package/`,gradle → `build/libs/`,maven → `target/*.jar`,三者都在构建树内。
uv 有 `dist/`,但 **uv 根本没有项目级 `clean`**,所以不会产生「clean 该删哪棵树」的歧义。

mjm 同时具备「产物在树外」与「有项目级 clean」,于是付出了这些代价(都是本仓库真实发生过的):

```
clean [--target|--dist]                    两个旗标
DistOutput.clear 的「两个调用者」注释       一个辅助类承担两种语义
挂载预检要按"请求的树"逐棵判                 非原子预检那次修补
clean 默认只清 target/、dist 要显式          为了安全打的补丁
```

**如果只有一棵树,以上四条全部消失**。详见 §5 A1。

### 2.2 事实二:全局缓存已经统一到 XDG,只有 mjm 和 maven/cargo 在旧位置

实测:`uv cache dir` → `~/.cache/uv`;`go env GOCACHE` → `~/.cache/go-build`;bazel → `~/.cache/bazel`。
maven `~/.m2`、cargo `~/.cargo` 是前 XDG 时代的选择;mjm 的 `~/.mjm` 与它们同辈,但比 XDG 晚。

uv 缓存还有一个值得抄的细节:子目录**带格式版本号**(`archive-v0`、`sdists-v10`、`interpreter-v5`)。
这样缓存布局演进时不必兼容旧结构,换号即可;旧目录由 `uv cache prune` 回收。

mjm 这边实测:`~/.mjm/store` 3.4M、`~/.mjm/locks` **177 个文件 / 28K**(基本上是本轮开发中
手工跑出来的),`~/.mjm/sources` 尚未创建。锁文件只增不减,`REMINDS.md` §11 记的就是这条。

### 2.3 事实三:清单体量,mjm 已经最小

- `uv init` 生成 6 项(实测:`.git` `.gitignore` `.python-version` `README.md` `pyproject.toml` `src/`)+ 一个 `uv.lock`
- mjm `init` 只生成一个 `mjm.toml`

uv 的 `.python-version` 本质是「工具链版本进仓库」;mjm 的 `[java].version` 已等价且省一个文件。
**这条轴上不需要向任何人学习。**

---

## 3. 语义轴对比

| 轴 | cargo | go | uv | pnpm | bazel | gradle | **mjm** |
|---|---|---|---|---|---|---|---|
| 解析 | 单版本 + resolver | MVS | 回溯求解 | 单版本 | 无(直接声明) | 最近版本 + 冲突策略 | **MVS + 跨 major 硬错** |
| 可见性 | 传递可见 | 传递可见 | 传递可见 | 严格(`.pnpm` 隔离) | 严格(显式 deps) | `api`/`implementation` | **严格(只直接依赖)** |
| 缓存 | registry + 源码 | module cache + build cache | 全局 CAS | store + 硬链 | action cache | caches/modules-2 | **全局 CAS,classpath 直指** |
| 工具链 | `rust-toolchain.toml` | `GOTOOLCHAIN` | `uv python install` | 无 | 自带 JDK 管理 | toolchains 自动下载 | `[java].version`(只校验,不下载——2026-11-22 明确放弃 `mjm jdk`) |
| 工作区 | `[workspace]` 继承 | `go.work`(不提交) | `uv workspace` | `pnpm-workspace.yaml` | 天然 | `settings.gradle` | V2(`mjm.ws.toml`,学 `go.work`) |
| 发布 | crates.io + `registry` 属性 | **无**(tag 即发布) | PyPI + index | npm registry | — | maven-publish | **去中心化**(`gh:` / `mjm:` / http PUT) |
| 机器接口 | `cargo metadata` | `go list -json` | `uv export` | `pnpm ls --json` | `query --output=json` | `dependencies` | **无** |

### 3.1 两处 mjm 已经比原版更彻底

**可见性**——pnpm 的隔离靠 `node_modules/.pnpm` 的物理结构达成;mjm 的 classpath 由自己拼装,
零成本做到同样隔离,并且多了一条 pnpm 没有的规则(深度预算)。

**发布**——cargo 的 registry 选择(`registry` 属性)与依赖声明是两套;mjm 的
`gh:owner/repo@tag` / `mine:1.2.3` / 裸 URL 与 `publish gh:` / `publish mjm:` / `publish mjm:local:`
是**同一套语法**(Maven 布局可读但不可发布)。横向对比里这也是独一份。

---

## 4. 可吸收清单

排序依据是**「它能删掉多少东西」**,不是「它能加多少功能」。

### A1 ★★★ 把 `dist/` 并进 `target/` — **已实施**

- **吸收谁**:cargo(`target/package/`)、gradle(`build/libs/`)、maven(`target/*.jar`)——可发布产物就在构建树里
- **删掉什么**:`clean` 的两个旗标、clean 的默认值争议、`DistOutput.clear` 的双语义注释、
  挂载预检按树分支的逻辑的一半
- **新形态**
  ```
  target/classes
  target/test-classes
  target/dist/        ← 原 dist/ 移入
  ```
  `mjm clean` = 删 `target/`,一条命令,没有旗标
- **代价**:用户脚本里 `dist/*.tar.gz` 要改;`publish` 的默认读取路径要改。**breaking**,
  需要一版过渡(两个位置都写、读优先新位置)。必须在用户规模还小的时候做——现在最便宜
- **动 manifest**:否

### A2 ★★ 加 `cache` 动词(学 uv) — **已实施**

- **吸收谁**:uv 的 `cache {clean,prune,dir,size}`(实测);bazel 的 `clean --expunge` 作对照
- **它替谁**:`~/.mjm/{store,sources,locks}` 目前没有任何查看/清理手段。`REMINDS.md` 里两条债
  都源于此:§11「锁文件不回收」、store 损坏时只能手删(错误信息里写的也是「手删」)
- **形态**(默认全部只读)
  ```
  mjm cache dir      # 三个目录的路径
  mjm cache size     # 各自占用
  mjm cache prune    # 删无人引用的 digest + 过期锁文件,打印释放量
  ```
  `clean` 永远不碰 store(哲学不变),清理是**显式动词**——两者职责正好互补:clean 管项目、cache 管全局
- **代价**:低。三个子命令,无破坏性默认
- **动 manifest**:否

### A3 ★★ 全局目录迁到 XDG — **已实施**

- **吸收谁**:uv / go / bazel(实测都在 `~/.cache` 下)
- **形态**
  ```
  ~/.mjm/store   → ${XDG_CACHE_HOME:-~/.cache}/mjm/store
  ~/.mjm/sources → 同上
  ~/.mjm/locks   → ${XDG_STATE_HOME:-~/.local/state}/mjm/locks
  ```
- **代价**:一次性迁移;老目录存在则继续用并在 stderr 提一句
- **动 manifest**:否

### A4 ★★ 缓存目录带格式版本号

- **吸收谁**:uv 的 `archive-v0` / `sdists-v10`
- **现状**:mjm 的 store 是 `<sha256>.<ext>` 平铺,扩展名策略就是事实上的格式版本,真要换布局没有退路
- **代价**:加一层 `v1/` 就够。**等真有布局变更时再做**,现在只记录
- **动 manifest**:否

### A5 ★ `lock --check`

- **吸收谁**:uv 的 `--locked`、`npm ci` 的语义
- **形态**:`mjm lock --check` 不写任何文件;锁与清单一致 → 0,否则 → 1 并说明差异
- **不要加 `--frozen`**:mjm 的锁默认从不被隐式重写,这比 uv 更严;加旗标反而是倒退
- **代价**:极小;**动 manifest**:否

### A6 ★ 机器可读输出,只给两个命令

- **吸收谁**:`cargo metadata`、`go list -json`、`pnpm ls --json`
- **范围**:只给 `outdated` 与 `tree` 加 `--json`。理由:这两个是 CI 与编辑器唯一会消费的;
  其余命令人读即可。**不是**给所有命令加
- **动 manifest**:否

### A7 ★ 工作区 — **已实施**(0.2.9/0.2.10)

`go.work`(不提交的本地覆盖文件)优于 cargo 的 `[workspace]` 清单继承:前者只是本机组合,
后者要求把 monorepo 关系固化进仓库。mjm 的实现(`mjm.ws.toml`,gitignore 不提交)按前者,
保持。

---

## 5. 不吸收

| 不吸收 | 理由 |
|---|---|
| bazel 的 query/target 语言 | `tree`/`why` 已覆盖 80%;查询语言 = 一门新 DSL |
| gradle 的 build script + 插件 | 已有红线(零插件);再加一条:脚本即配置,会让 `mjm.toml` 变成程序 |
| uv 的 `pip` 兼容层 | mjm 与 Maven 的关系是**只读互操作**,不是替代品 |
| npm 的 lifecycle scripts | `[scripts]` 是命令别名,不是第二个生命周期 |
| npm 的 `audit` | 漏洞库是另一个产品;DESIGN 已把它排在 V2 且用 OSV.dev,不属于「命令清晰」范畴 |
| **M6 daemon(建议重新评估)** | 横向看:只有 bazel 与 gradle 跑常驻 daemon,而它们都是「构建图 + 多语言 + 增量」的重量级系统。cargo / uv / npm / go 全都没有,因为进程启动成本可忽略。mjm 现在是进程内 javac,本仓 66 个源文件全量编译 + 264 个测试在一次 `mjm test` 里跑完——**在实测到「构建 > 5s」之前建 daemon 是负资产**(多一个生命周期、一套失效语义、一种调试状态) |

---

## 6. 实施记录

原计划的顺序是 A5/A6 → A2 → A3 → A1(前者给后者铺路),实际都在 **0.2.5** 一次性发出。
项目还处在 0.2 段,版本号由维护者按「不跳大版本」的偏好决定,所以没有为 A1 单开一个版本。

| 条目 | 状态 | 落在哪 |
|---|---|---|
| A1 `dist/` → `target/dist/` | ✅ 已实施 | 0.2.5,**唯一 breaking**:读 `dist/` 的脚本要改成 `target/dist/`;旧目录仍在时 `publish` 会明确报错而不是「文件找不到」 |
| A2 `cache {dir,size,prune,clean}` | ✅ 已实施 | 0.2.5;`REMINDS §11`(锁文件不回收)随之解决 |
| A3 XDG 路径 | ✅ 已实施 | 0.2.5;旧 `~/.mjm` 存在则优先用它,不强行搬家 |
| A4 缓存目录带格式版本号 | ⏸ 暂缓 | 等真要改 store 布局时再做,现在记着 |
| A5 `lock --check` | ✅ 已实施 | 0.2.5 |
| A6 `tree/outdated --json` | ✅ 已实施 | 0.2.5 |
| A7 工作区 | ✅ 已实施 | 0.2.9 收口 classpath 装配,0.2.10 落地 `mjm.ws.toml`/`init --ws`、替换与根目录编排;仍是 `go.work` 式本地文件,不提交 |

### 实施后再看:哪里比预期好,哪里比预期差

**好于预期**:A1 一落地,四条复杂度同时消失——`clean` 的旗标语义、`DistOutput` 的双语义注释、
两棵树的挂载预检分支、clean 默认值的争议。这类「删掉一个决定就少四条路径」的改动,
比任何新功能都值。

**差于预期**:A1 是对外可见的破坏。虽然给了迁移提示(旧 `dist/` 存在时 `publish` 报一句
可执行的话),但**用户脚本里的 `dist/*.tar.gz` 仍然会失效**——文档能减轻,不能免除。
这也是为什么它本应单开一个版本;合并进 0.2.5 是版本序列偏好压过语义版本的结果,
发布说明里必须把它写在第一条。

**还没做**:A3 的兼容期何时结束没有定日子,判据是「连续两版发布说明都写了 XDG 迁移条目,
且没有用户报告找不到依赖/锁」——到了再删旧路径回退。

---

## 7. 存疑与未验证

- cargo / pnpm / bazel / gradle 的目录与命令面**未在本机复跑**(见 §0.2)。若要据此做决定,
  建议先在有这些工具的环境里补测一轮,尤其是 cargo 的 `target/package/` 与 gradle 的 `build/libs/`。
- 「M6 daemon 不必做」的结论基于**本仓库**的构建耗时(66 个源文件);上千文件的项目未测。
  真要决策,应先在一个大项目上量一次 `mjm build` 的墙钟时间。
- uv 的缓存子目录版本号只在 0.5.x 观察到,官方是否把它当稳定约定未确认。

---

## 附录:功能缺口与吸收清单(自 DESIGN §13 移入,历史快照)

> 下表描述 2026-11-23 时的状态;当前能力以 [SPEC.md](../SPEC.md) §6 与 `mjm --help` 为准。

### P0 — 闭环缺口

| # | 缺口 | 对标 | 状态 |
|---|---|---|---|
| 0 | `mjm source <id>`(替代 sources jar)+ `mjm publish` 交接指令 | Go tag-archive、pkg.go.dev 可寻址源码;npm 的 `npm pack` 分享 | ✅ 已完成(2026-11-21) |
| 1 | `[dev-deps]` 依赖分组 | Cargo dev-dependencies、uv PEP 735 dependency-groups | ✅ 已完成:独立顶层节,进同一张锁图,`scope = "dev"`;只进 `mjm test`,MAIN 优先 |
| 2 | `mjm init --pom` / `mjm remove` / `mjm clean` | mvn clean、npm init/uninstall、Maven 迁移 | ✅ 全部完成(clean 含 `[--target] [--dist]`,永不碰 store) |
| 3 | `mjm info <id> [--source]` | Go/Cargo 源码随 tag 走 | ✅ 已完成:gh: 取 tag archive,Central 回退 sources jar,URL 叶子拒绝 |
| 4 | JUnit XML 测试报告 | mvn surefire | ✅ 已完成:`target/test-results/TEST-<类名>.xml`,无 timestamp、同一套测试两次跑字节相同 |
| 5 | `mjm outdated` | mvn versions、npm outdated | ✅ 已完成:`maven-metadata.xml` 列版本,预发布按 qualifier token,未检查项具名 |
| 5b | `mjm audit` | cargo audit / npm audit | ✅ 已完成:OSV `querybatch` + `vulns/{id}`,未检查项给原因,失败具名 |
| 10 | 可复现发行物 | Maven reproducible builds | ✅ 已完成(2026-11-23):固定 DOS 时间与 creation/access time,条目字典序,两次 package 逐字节相同 |

### P1 — 现代实践(未做)

| # | 缺口 | 对标 |
|---|---|---|
| 6 | 发版前 API 兼容检查 | cargo-semver-checks、japicmp、Revapi |
| 7 | workspace 依赖继承 | Cargo `[workspace.dependencies]`、Gradle version catalogs |
| 8 | `mjm publish --dry-run` | cargo publish --dry-run、npm pack |
| 9 | `--offline` | cargo --offline、pnpm --offline |

### P2 — 记录缓行

- 增量编译/构建缓存(Gradle build cache、cargo incremental)
- 测试并行(Gradle maxParallelForks)
- `git:` 任意仓库物种
- 注解处理器(`[build].parameters` 传 `-processorpath` 即可)
- JPMS module-path

### 明确不吸收(品味红线)

- **Maven profiles**:配置面泛滥的根源;mjm 用环境外置 + `[scripts]`
- **插件生态**:零插件哲学是 mjm 的身份
- **npm 式语义版本范围**(`^1.2.3`):精确钉定 + lockfile 双钉是供应链优势
