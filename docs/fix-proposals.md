# 修复方案（未实施）

本文件记录两个已知缺陷的书面方案：它们都涉及跨机器/跨目录的可复现性，或写盘次序，改动面比
一行修复大，先出方案再排期。**这里没有实现，代码未动。**

对应复现记录见 `logs/` 下的测试日志，以及测试报告中的 F1 / F6。

---

## F1 — `local:` 仓库让 mjm.lock 无法跨目录复现

### 现状

- `[repos] vendor = "local:vendor"` 在解析时被展开成绝对 `file://` URL
  （`ArtifactLocations.mavenFile` → `asFileUrl`），锁文件里每一行的 `url` 都长这样：
  `file:///home/dzb/Projects/mjm-cargo/vendor/com/jujin8/freeway/…/freeway-commons-2.0.2.jar`。
- 后果一：换一个目录（或换一台机器）跑 `mjm lock`，9 行的 `url` 全部改写，工作区立刻变脏。
  仓库里那份 `mjm.lock` 就是在 `/home/dzb/Projects/mj` 生成的，搬到这里之后 `git diff` 就是 9 行漂移。
- 后果二：新机器 `git clone` + 空 store 直接 `mjm build`
  → `mjm: com.jujin8.freeway:freeway-commons@2.0.2 is not in the store — run mjm lock`。
  即“clone 下来自举”只在“store 已经暖过”的机器上成立。
- 后果三：`--offline` 在搬迁后的检出上直接失败。持久 URL→digest 索引
  （`store/index/<sha256(url)>.url`）按**绝对 URL** 记录，换目录后索引全部未命中，
  而 `--offline` 又禁止重新取，于是即便每个字节都已在 store 里也报缺。
- 不受影响的：sha256 双钉本身是对的（字节级校验有效）；漂移的只是“去哪儿取”这一列。

### 方案 ①：锁文件里存项目相对路径，读取时按项目根展开（推荐）

- 形态：`local:` 仓库的 URL 写成相对项目根的路径，例如 `file:vendor/com/.../x.jar`
  （或自定义 `local:vendor/...` 前缀），由读取侧拼接项目根。
- 改动面：`ArtifactLocations`（拼接与展开）、`LockFile`/`LockedArtifact`（URL 表示）、
  `ArtifactStore.ensure` 与 `UrlIndex` 的键（相对 URL 才能命中）、`Resolver` 里组装工件 URL 的位置。
- lock 兼容性：**会破旧 lock 的 URL 格式**，需要读取侧两分支——见到 `file://` 绝对形式按旧语义处理
  （仍然能用，但换机器时照旧失败），见到相对形式按项目根展开；写出时一律用新形式。
  两分支的判定只看前缀，不需要版本号字段。
- 代价：URL 语义从“全局位置”变成“项目内位置”，`mjm info`/`mjm publish` 打印的 URL 也要跟着解释；
  `local:` 的绝对路径写法（`local:/abs/path`）要么拒绝，要么继续按绝对处理并标记不可移植。
- 回归测试断言：把同一份 fixture 项目复制到两个不同临时目录，各自 `mjm lock`，两次写出的
  `mjm.lock` **字节相同**；再用空 store 在副本目录里 `mjm build` 成功。

### 方案 ②：保留绝对 URL，读取时“store 命中即忽略主机失败”

- 形态：`mjm.lock` 不变；`ArtifactStore.ensure` 在 URL 取不到（file 不存在 / HTTP 404）时，
  若该工件的 sha256 已经在 store 里，直接用 store 命中，不再报错。
- 改动面：`ArtifactStore.ensure` 的失败分支 + `LockService`/`BuildCommand` 里“先按 URL 取、
  再按 digest 校验”的次序；锁文件与序列化不动。
- lock 兼容性：**完全兼容**，旧 lock 原样可读。
- 代价：这正是 `--offline` 明确拒绝的“静默降级”味道——URL 取不到就悄悄用 store，
  掩盖了“源已经不在”的事实；而且不解决“重锁会改写 9 行 URL”的脏工作区问题，
  只解决“暖 store 的机器能 build”。`--offline` 下更说不清是命中还是失败。
- 回归测试断言：空 store + 坏 URL + 正确 sha256 → `ensure` 报错；暖 store + 坏 URL →
  成功且报告使用了 store 副本（不得静默）。

### 方案 ③：锁文件只记“仓库 id + sha256”，URL 不进锁

- 形态：每行记录 `repo = "vendor"`、相对路径（`com/.../x.jar`）与双钉；URL 在读取时由
  `[repos]` 配置现场拼。缺 `[repos]` 声明时按 Central 隐式默认。
- 改动面：`LockedArtifact`（字段变化）、`LockFile`/`LockFileReader`（格式）、`Resolver`
  的 lock 写出行、`ArtifactStore.ensure` 的入参（从 URL 变成 repo+path），以及
  `mjm info`/`mjm publish` 的 URL 展示。
- lock 兼容性：**明确破格式**（字段级），需要双格式读取器：有 `url` 按旧路径走，
  有 `repo+path` 按新路径拼；写出统一新格式。
- 代价：改动最大，但收益最彻底——锁文件只留事实（身份 + 字节），“去哪儿取”回到配置；
  和 SPEC §3“发布物里只有事实，规则只有一份”的取向一致。
- 回归测试断言：同一 fixture 在两个目录各锁一次，lock 字节相同；`[repos]` 改名后
  旧 lock 报“仓库 'vendor' 未声明”而不是静默换源。

### 推荐

先做 ①：它解决三个后果（脏工作区、新机器 build、`--offline` 命中）且不需要动 `LockedArtifact`
的字段语义，旧 lock 有明确的兼容读取分支。③ 是终局形态，等 workspaces/V2 一起排；
②单独做收益最小，且与 `--offline` 的“不许静默降级”冲突，不建议。

---

## F6 — `mjm add` 失败后不回滚，把项目写坏

### 现状

`AddCommand.execute` 的次序是：校验形态 → `appendToDeps` 得到新文本 → `ProjectReader.parse(updated)`
→ **写 mjm.toml** → `lockService.refresh(projectDir)`。

`refresh` 才是真正取元数据、解析、写 mjm.lock 的一步。它在 `add` 里失败（例如坐标只在
`[repos]` 里存在、而 `add` 按 Central 物种写进 `[deps]`；或网络不可用），此时：

- `mjm.toml` 已经被改动，多了一条无法解析的声明；
- `mjm.lock` 没有更新，于是紧随其后的 `mjm build` 报
  `mjm.lock is stale — '…' is declared in mjm.toml but not locked; run mjm lock`，
  而 `mjm lock` 又会因为同一条声明再次失败；
- 用户只能手工删掉那一行才能恢复。可复现步骤见测试报告 F6。

### 方案 ①：先在内存里解析成功，再落盘（推荐）

- 形态：把 `refresh` 拆成“解析（纯内存，返回图）”与“写盘（mjm.lock）”；`add` 先对新文本跑
  解析，解析通过后才写 mjm.toml 和 lock。
- 改动面：`LockService`（拆出 resolve-only 入口）、`AddCommand.execute`（次序调整）。
- 代价：`refresh` 目前一步完成“下载 + 写锁”，拆开后 `add` 以外仍然复用整条流程；
  下载失败（字节能解析但取不到）仍需回滚，见方案 ③。
- 回归测试断言：在一个只有 `[repos] vendor` 的 fixture 项目里 `add` 一个不存在的坐标 →
  exit 1，且 `mjm.toml` 字节与调用前相同；随后 `mjm lock` 仍然成功。

### 方案 ②：落盘前备份，失败后原样还原

- 形态：读旧文本 → 写新文本 → 失败则写回旧文本（同样的字节）。
- 改动面：只动 `AddCommand`（一处 try/catch），最小。
- 代价：回滚本身也可能失败（磁盘满、权限）；回滚窗口里进程被杀则文件仍是坏的；
  没有解决“lock 与 toml 不同步”的语义，只是把窗口缩小。
- 回归测试断言：同 ①，断言失败后 `mjm.toml` 与旧字节一致。

### 方案 ③：临时文件 + 原子 rename，失败再还原

- 形态：新文本写 `mjm.toml.tmp`，`refresh` 成功后原子 `rename` 覆盖；失败则删除临时文件。
  （`ArtifactStore` 写 store 文件时已经是这个模式，可直接照搬。）
- 改动面：`AddCommand` + `LockService` 的写 lock 位置（保持同一提交点语义）。
- 代价：两处原子写（toml 与 lock）无法真正同时提交，仍有一个极短窗口；
  但读者要么看到旧 toml，要么看到新 toml，不会看到半个文件。
- 回归测试断言：失败路径上目录里没有 `mjm.toml.tmp` 残留，`mjm.toml` 字节不变。

### 推荐

①+③ 组合：先内存解析（挡掉语义错误），再用临时文件原子替换（挡掉写盘与刷新失败）。
只做 ① 仍会在“解析通过但下载失败”时留下半死状态；只做 ③ 会把语义错误的回滚依赖在
失败分支上。
