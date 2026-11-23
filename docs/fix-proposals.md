# 修复方案

本文件记录两个缺陷的方案。**F1 已按方案 ① 实施**（原文保留作为决策记录，现状一节记录的是
实施前的问题）；**F6 仍是方案，代码未动。**

---

## F1 — `local:` 仓库让 jeno.lock 绑定 checkout 的绝对路径（**已实施**，方案 ①）

### 现状（复现已修正）

- `[repos] vendor = "local:vendor"` 在解析时被 `ArtifactLocations.asFileUrl` 变成**绝对**
  `file://` URL：相对路径按当前工作目录解析（`JenoModule` 把 cwd 固定成项目根），`~` 展开成
  家目录。锁文件里每一行的 `url` 因此长这样：
  `file:///home/dzb/Projects/jeno-cargo/vendor/com/jujin8/freeway/…/x.jar`。
- 仓库里 committed 的那份 `jeno.lock` 指向**另一份 checkout**
  `/home/dzb/Projects/mj/vendor/…`。那个目录存在，`vendor/` 也被 git 跟踪——不是"文件丢了"，
  而是"锁文件记的是另一个目录的绝对位置"。
- 同一台机器 `git clone` 到 `/tmp/x` 后 `jeno lock` 是**成功**的：`local:vendor` 按新的工作目录
  解析，9 个工件都在新 checkout 里。真正的破坏是下面四条，都只在"换目录/换机器"时出现：
  1. **冷 store + committed lock**：`jeno build` 只走 `verifiedJars`（纯 store 查询），缺件就报
     `run jeno lock`；按锁里的 URL 取字节的是 `jeno lock` 自己。committed lock 指向另一份 checkout，
     所以冷 store 的新 clone 在 build 这一步就停住，重锁又会把 9 行 URL 改写成当前目录。
     实测新 clone + 空 store（`-Duser.home=/tmp/empty`）→
     `jeno: com.jujin8.freeway:freeway-commons@2.0.2 is not in the store — run jeno lock`。
     换到一台没有 `/home/dzb/Projects/mj` 的机器，锁里的 URL 直接无处可取。
  2. **重锁漂移**：在任何别的目录跑 `jeno lock`，9 行 `url` 全部改写 → 工作区变脏，
     "锁文件字节稳定"不成立（同一目录内重锁才幂等）。
  3. **`--offline` 在搬迁后的检出上失败**：store 的 URL→digest 索引（`store/index/<sha256(url)>.url`，
     `UrlIndex`）是按**绝对 URL** 做 key 的。换目录后索引全部未命中，`--offline` 又禁止重新取，
     于是即便每个字节都已经在 store 里也报缺（实测 `lock --offline` exit 1）。
  4. `jeno info` / 错误消息里展示的 URL 也是绝对路径，无法在同事的机器上使用。
- 不受影响的：sha256 双钉本身正确——漂移的只是"去哪儿取"这一列，字节校验仍然有效。

### 方案 ①：`local:` 在锁文件里用相对项目根的表示（推荐）

- 形态：锁里写 `file:vendor/com/.../x.jar`（或 `source = "local:vendor"` + `path = "com/.../x.jar"`
  两列）。读取时由项目根展开成绝对 URL 再交给 store。
- 改动面：`ArtifactLocations`（`asFileUrl` 拆成"写锁用相对 / 读取用展开"两个方向，
  并让 `local:` 相对**项目根**而不是 cwd）、`LockedArtifact`/`LockFile`（URL 列的表示）、
  `ArtifactStore.ensure` 的入参（需要项目根上下文，或在调用前已展开成绝对 URL）、
  `UrlIndex` 的 key（改用相对 URL，才能跨目录命中）、`JenoModule`（把 `projectDir` 传给展开点）。
- lock 兼容性：**旧 lock 仍可读**。按前缀分支：`file://` 绝对形式走旧语义（能用，但仍不可移植）；
  相对形式按项目根展开。写出统一用新形式，不需要版本字段。
- 代价：URL 的语义从"全局位置"变成"项目内位置"，`info`/错误消息/`lock` 打印 URL 的地方要统一
  按同一规则显示；`local:/abs/path` 要么拒绝，要么保留并明确标注不可移植。
- 回归断言：
  1. 同一 fixture clone 到两个不同目录，各自 `jeno lock`，两份 `jeno.lock` **字节相同**；
  2. 在第二个目录用**空 store** 的 `--offline` 跑 `jeno lock`（store 先由第一个目录预热）
     不再因索引未命中而失败；
  3. 不重锁、直接 `build` 也能用（URL 相对化后指向本 checkout）。

### 方案 ②：保留绝对 URL，读取时"sha256 已在 store 就不管 URL"

- 形态：锁格式不动。`ensure` 取不到 URL（file 不存在 / 404）时，若该工件的双钉字节已在 store，
  直接用 store 命中。
- 改动面：`ArtifactStore.ensure` 的失败分支、`LockService`/`BuildCommand` 里"先取后校验"的次序；
  锁与序列化不动。
- lock 兼容性：完全兼容。
- 代价：这正是 `--offline` 明确拒绝的"静默降级"——取不到就悄悄用旧字节，掩盖"源不在"；
  而且**不解决** 1 个脏工作区问题（重锁仍漂 9 行），只让"暖 store 的机器能 build"。
- 回归断言：空 store + 坏 URL + 正确 sha256 → 报错；暖 store + 坏 URL → 成功且**明确报告**
  用了 store 副本（不得静默）。

### 方案 ③：锁里只存仓库身份 + 相对路径，URL 不进锁

- 形态：每行 `repo = "vendor"`、`path = "com/.../x.jar"`、双钉；URL 读取时由 `[repos]` 现场拼。
- 改动面：`LockedArtifact`（字段）、`LockFile`/`LockFileReader`（格式）、`Resolver` 的写锁行、
  `ArtifactStore.ensure` 入参、`UrlIndex` key、`info`/publish 的 URL 展示。
- lock 兼容性：**破格式**（字段级），需要双格式读取器：有 `url` 按旧路径走，有 `repo+path`
  按新路径拼；写出统一新格式。
- 代价：改动最大，但最彻底："去哪儿取"回到配置，锁里只有事实（身份 + 字节）。
- 回归断言：同一 fixture 两个目录各锁一次 → 字节相同；`[repos]` 改名后旧 lock 报
  "仓库 'vendor' 未声明"，而不是静默换源。

**推荐**：先做 ①。它同时解决漂移、新机器不可用、`--offline` 索引未命中，且旧 lock 有明确兼容分支。
③ 是终局形态，等 workspaces/V2 一起排。②与 `--offline` 的"不许静默降级"冲突，不建议单独做。

---

## F6 — `add` / `remove` 失败后不回滚

### F6a：prepare-then-commit（推荐主方案）

**现状**：`AddCommand` 与 `RemoveCommand` 的次序都是
"读 jeno.toml → 算出新文本 → 内存里 parse 校验 → **写 jeno.toml** → `lockService.refresh`"。
`refresh` 才是取元数据、解析、写 jeno.lock 的一步。它在写盘之后失败（坐标只在 `[repos]` 里、
网络不可用、坐标拼错但在 Central 上不存在…）时：

- `jeno.toml` 已被改动，多了一条解析不了的声明；
- `jeno.lock` 没更新，于是 `jeno build` 报 `jeno.lock is stale — '…' is declared … but not locked`，
  而 `jeno lock` 又会因为同一条声明再次失败；
- 用户只能手改 jeno.toml（复现见测试报告 F6）。

**方案**：把"准备"与"提交"分开，两个写盘点放在解析与下载都成功之后：

1. 读 jeno.toml，算出新文本，`ProjectReader.parse(updated)` 校验（纯内存，不写盘）；
2. 用 `ProjectReader.parse(updated)` 的结果做一次**完整 resolve**（纯内存，不写 jeno.lock）；
3. 按 resolve 结果 `store.ensure` 每一个工件（下载/校验字节；失败则中止，磁盘无改动）；
4. 提交：写 jeno.toml，再写 jeno.lock（同一个提交点，顺序固定）；
5. 失败处理：第 1–3 步任一步失败 —— 磁盘无改动，直接退出；第 4 步若 jeno.toml 已写而 jeno.lock
   写失败 —— 立即写回旧 jeno.toml（第 2 步已把它的字节留在内存里），再退出。

**改动面**：`LockService` 拆成 `prepare(projectDir, text)`（返回图 + 字节都已就位）与
`commit(projectDir, text, graph)`（写 jeno.toml + lock）；`AddCommand`/`RemoveCommand` 改调两者；
两个命令的"写盘"合并成一个提交方法。

**为什么不是只做原子 rename**：rename 只能保证"文件是完整的"，不能保证"语义是成功的"——
下载失败时仍需要一次明确的回滚点，而 prepare-then-commit 让失败路径根本不产生写。

**回归断言**：
- 在一个只有 `[repos] vendor` 的 fixture 项目里 `add` 一个不存在的坐标 → exit 1，
  且 `jeno.toml`、`jeno.lock` 与调用前**逐字节相同**（不是"语义相同"：比对文件内容）；
- 同样的断言对 `remove` 一条会导致 refresh 失败的声明成立（例如锁里已有工件、store 被清空）；
- 成功后：jeno.toml 与 jeno.lock 都体现新图，且此时目录里没有 `*.tmp` 残留。

### F6b：`add` 支持 native / repo-bound 物种

**现状**：`add <key> <value>` 的形态校验已经认识 `gh:`/`gitee:`/`https://…jar`，但落盘位置
永远是 `[deps]`（Central 组）。于是：

- 声明属于 `[repos]` 的依赖只能是 `group:artifact` + **Central** 物种，写进 `[deps]` 后
  解析会去 Central 取，取不到（实测：`add "com.jujin8.freeway:freeway-commons" 2.0.2`
  → `unable to fetch the pom`，且此时 jeno.toml 已被写坏）；
- 没有任何方式让 `add` 写进 `[deps.{repo}]`，也没有 native 版本的发现（`versions()` 只查
  Central 的 `maven-metadata.xml`，`search()` 只查 Central 索引）。

**方案（三选一，未定）**：
1. `add --repo <name>`：把声明写进 `[deps.<name>]`（或 `[dev-deps.<name>]`），物种由值形态决定；
   无 `--repo` 时保持今天的 Central 行为。改动面：`AddCommand`（参数解析 + 写入位置）、
   `appendToDeps` 的节选择；`versions()` 对 repo-bound 坐标给出"该仓库无版本列表"的明确错误。
2. `add` 拒绝 repo-bound 坐标并说明：`group:artifact` 只用于 Central；仓库依赖请手写
   `[deps.<repo>]` 并给一条示例。改动面最小，只是把 F6a 的失败前置成预检。
3. native 版本发现：`add <owner>/<repo>@<tag>` 直接写 `[deps]` 的 `gh:`/`gitee:` 形态
   （今天的值形态校验已支持，缺的是从 tag 推导 group/artifact 的规则与版本列表来源）。

**回归断言**：`add --repo vendor "com.jujin8.freeway:freeway-commons" 2.0.2` 成功，
`jeno.toml` 里出现在 `[deps.vendor]` 而不是 `[deps]`；`add` 同一坐标不带 `--repo` 时
（若选方案 2）exit 1 且文件逐字节不变。
