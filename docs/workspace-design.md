# 多模块设计草案 v2（workspaces）

状态:**草案,待评审,未实现**
更新:2026-11-22(首版被评审驳回 R35/R36/R40,本版重写)

首版的前提「替换点只有一处」是错的:今天有**四个**独立的 classpath 装配点
(`BuildCommand`、`TestCommand`、`RunCommand`、`ClasspathCommand`),另有两个打包
路径(`package --uber`、`bundle`)直接读 store 里的 jar。本版按这个事实重写。

---

## 1. 问题

**A. monorepo** —— 一个仓库多个模块,共享依赖,一起构建。
**B. 跨仓调试** —— 依赖的库就在隔壁,想改一行立刻看到效果。

## 2. 目标与非目标

**目标**

1. 同目录树里的模块能被另一个模块**替换**,不需要发布。
2. 工作区根目录能一次跑 `lock` / `build` / `test` / `clean`,按依赖顺序。
3. **不改变任何现有文件格式**:`mjm.toml`、`mjm.lock`、描述符、`[repos]` 不动。
4. 模块单独拿出去仍完全可用。
5. 不设工作区时,CI 看到的一切与今天一致。

**非目标**

- 不做构建图引擎;不做跨模块版本仲裁;不做版本目录(§10 Q4)。

## 3. 核心决定:替换发生在装配 classpath 时,而且要先把装配收成一处

依赖 `com.acme:core:1.2.0`,工作区里 `libs/core` 产出它:

1. **照常解析、照常锁** —— 锁里写的就是仓库里的 `core:1.2.0`,不含任何本地路径。
2. **装配 classpath 时,把那一行 jar 换成 `libs/core/target/classes`**。

换来四件事不变:锁格式、版本语义、严格可见性、深度预算。

**但这不是免费的。** 今天四个命令各自拼 classpath:

| 位置 | 拼的是什么 |
|---|---|
| `BuildCommand:95-102` | 编译 classpath(直接依赖的 jar) |
| `TestCommand:113/119` | 测试编译 classpath + fork 的 runtime classpath |
| `RunCommand:159` | runtime classpath |
| `ClasspathCommand` | 导出的两条 classpath |

实现的第一步是**抽出唯一的 `ClasspathAssembler`**,四个调用点全部改为经过它,
替换只在这个 seam 里发生。没有这一步,工作区会以四种略微不同的方式生效——那比没有
工作区更糟。

## 4. 命令 × 是否替换(实现前必须钉死的表)

| 命令 | 替换? | 理由 |
|---|---|---|
| `build`(编译) | ✅ | 这就是工作区的意义 |
| `test`(测试编译 + fork runtime) | ✅ | 否则测不到本地改动 |
| `run`(runtime) | ✅ | 同上 |
| `classpath`(两种) | ✅ | IDE 因此能看到兄弟模块的 classes |
| `package`(普通 jar) | 无关 | 只含本项目 classes,不含依赖字节 |
| `package --uber` | ❌ **拒绝** | uber 会把依赖字节嵌进去;嵌入未发布的本地 classes 就等于产出一个**不可分发**的 jar |
| `bundle` | ❌ **拒绝** | 同理:发行目录里的 `lib/` 会带本地 classes,给别人就跑不起来或跑错 |
| `publish` | ❌ **拒绝** | 描述符只能写已发布版本;发布本地字节等于撒谎 |
| `info` / `tree` / `why` | 展示锁图 + 标记 | 它们答的是「锁里的图」,不是「本地字节」;命中替换的行加 `(local: <path>)` 标记 |
| `lock` | 无关 | 替换不参与解析 |

**拒绝的形态**:报错并说明——"`core` would come from libs/core, which is not a
published artifact; `--uber`/`bundle`/`publish` embed or describe published bytes,
so they refuse while a workspace override is active. Build and run the module
instead, or drop `libs/core` from mjm.work.toml." 不给静默降级。

## 5. 文件

```toml
# mjm.work.toml —— 工作区根目录,建议 gitignore(跨仓调试)或提交(monorepo)
[work]
modules = ["libs/core", "libs/util", "apps/web", "tools/cli"]

# 可选:允许本地版本与锁里的版本不一致(见 §6.2)。默认 false。
allow-version-skew = false
```

- 路径相对工作区根;必须是目录且含 `mjm.toml`。
- 查法:从当前目录向上找,取最近的一层;没有就是单模块(今天的行为)。
- 工作区根目录**不能**同时是模块(既有 `mjm.toml` 又有 `mjm.work.toml` → 报错)。
- 文件里除 `modules` 与 `allow-version-skew` 外**不携带任何规则**:版本、仓库、覆盖
  仍在各模块 `mjm.toml`——「规则只有一份」。

## 6. 语义

### 6.1 替换规则

模块 id 来自 `[project].group + .name`(无 group 时用 `name`,与原生工件约定一致)。
消费者的某个工件命中映射时:

| 检查 | 不通过 |
|---|---|
| 锁里的版本 == 本地模块版本 | 见 §6.2 |
| `target/classes` 存在 | 报错 `run mjm build in <path> first` |
| `target/classes/META-INF/mjm/version.properties` 里的版本 == 本地模块版本 | 报错:classes 是旧版本构建的,提示重新 build |

最后一条不需要额外的 stamp 文件:`mjm build` 本来就会往 `target/classes` 写
`META-INF/mjm/version.properties`,读它即可。这比比 mtime 可靠得多(git checkout
会把 mtime 全部刷新)。

### 6.2 版本不一致:默认拒绝,显式放宽

默认(以及 `allow-version-skew = false`)要求**精确相等**,否则报错——本地与 CI
分叉是 mjm 要消灭的东西。

但真实开发里"改进本地版本看效果"需要先改模块版本、再改所有消费者声明,那会让工作区
从"方便"变成"负担"。所以给一个**显式、非默认**的逃生口:

```toml
[work]
allow-version-skew = true
```

打开后允许本地版本 ≥ 锁里的版本,但**每次运行都打印一行警告**:

```
warning: core is 1.2.1 locally, 1.2.0 in the lock — CI will build against 1.2.0
```

**不允许静默**。若本地版本更低 → 仍然报错(那说明锁比本地新,替换没有意义)。

### 6.3 工作区根目录的编排

```
mjm lock     逐模块(拓扑序);遇错即停          ← 锁不一致比测试失败危险
mjm build    逐模块;遇错即停,末尾 `built: X, failed: Y`
mjm test     逐模块;遇错继续,末尾汇总          ← 与 CI 测试套件同构
mjm clean    逐模块;遇错继续,汇总              ← 一个模块的挂载/权限问题不该让其余不清
```

- 拓扑序由模块间依赖推出;**环 = 错误**并列出环上的路径。
- `--module <path>` 只处理一个(相对工作区根)。
- **非事务**:`build` 中途失败时已成功的模块保留 `target/classes`;重跑会复用它们。
  这不是 bug,但要写进输出(`built: X, failed: Y`)。
- 这些根命令**不接受**替换以外的语义:例如 `lock` 不会因为兄弟模块而少解析。

### 6.4 其他命令在根目录

`add` / `remove` / `package` / `bundle` / `publish` / `run` / `info` / `tree` / `why` /
`classpath` 在根目录**拒绝**执行,要求 `--module <path>` 或进入模块目录。
`tree` / `why` / `info` / `classpath` 支持 `--module`。

### 6.5 模块目录里

一切照旧,唯一区别是替换生效。`mjm add` **不感知工作区**:它按仓库解析;若兄弟模块
没有发布版本,报错并提示"先发布,或不要用 add 加这个依赖"。

### 6.6 IDE 的使用规则(必须写进文档)

- 只编辑一个模块时:用那个模块的 `mjm classpath`,兄弟模块的 `target/classes` 作为
  外部库出现。
- 想同时把两个模块当独立工程编辑时:各自用各自的 classpath,**不要**把替换后的
  consumer classpath 再加到兄弟工程上——同一批 classes 会从两个来源出现。
- 这不需要工具介入,但需要一句话说明,否则用户会自己撞上。

## 7. 错误与边界

| 情形 | 行为 |
|---|---|
| `modules` 里路径不存在 / 没有 `mjm.toml` | 报错并列出 |
| 两个模块产出同一个 id | 报错(不能让顺序决定胜负) |
| 两个模块 realpath 相同(符号链接绕一圈) | 报错 |
| 根目录同时有 `mjm.toml` 与 `mjm.work.toml` | 报错 |
| 模块间成环 | 报错并给出环 |
| 被替换模块未构建 | 报错 + `run mjm build in <path> first` |
| classes 的版本戳与模块版本不一致 | 报错:旧版本构建产物,先 build |
| 版本不等且未开 skew | 报错:双方版本 + 路径 + 两种解法 |
| 版本更低(skew 开着) | 仍然报错 |
| 嵌套 `mjm.work.toml` | 取最近的一层,忽略外层 |
| 运行中 `mjm.work.toml` 变了 | 不缓存;每次运行重新读取并重算映射 |
| 模块被移出 `modules` | 立即回到仓库版本;不改任何 lock;不警告(这是用户刚做的选择) |
| 兄弟 `target/classes` 比源码旧 | 不检查 mtime(不可靠);版本戳是唯一依据 |

## 8. 与既有机制的关系

| 机制 | 影响 |
|---|---|
| `mjm.lock` | 不变,每模块一份,照旧提交 |
| 项目锁 `~/.mjm/locks/<sha(realpath)>` | 不变;根目录编排时逐模块取锁 |
| 严格可见性 | 不变(替换只改字节来源) |
| `--offline` | 语义不变;兄弟模块少一次网络需求 |
| `mjm cache` | 不变;工作区不产生新的全局目录 |
| 深度预算 | 不变;预算看锁里的图,与字节来源无关 |
| `[scripts]` 的 `MJM_CLASSPATH` | 跟随替换(与 `run` 一致) |

## 9. 为什么不是另外两种做法

**Cargo 式:工作区共享一个锁** —— 语义更好(全工作区单版本),但锁的粒度从"每项目"
变成"每工作区",模块独立可用性下降,改动面大。留作 V2。

**把 `path = "../core"` 写进 `mjm.toml`** —— 拒绝:`mjm.toml` 是提交进仓库的,
路径会变成机器相关,正是 F1 刚修掉的病。

## 10. 待定问题(需要维护者拍板)

| # | 问题 | 建议 |
|---|---|---|
| Q1 | 版本相等要不要放宽 | 默认精确相等 + `allow-version-skew` 显式开关并每次警告(§6.2) |
| Q2 | 根目录要不要编排 `package`/`bundle` | V1 不编排;两者在替换命中时**拒绝**。需要全量打包时再加显式命令 |
| Q3 | `mjm.work.toml` 该不该提交 | 允许但不规定;monorepo 提交、跨仓调试 gitignore |
| Q4 | 版本目录(`[workspace.dependencies]` 那套) | V1 不做;要做就单独一个文件,不塞进 `mjm.work.toml` |

## 11. 验收标准(实现时照此写测试)

1. A→B 声明;B 构建后,A 的 `mjm classpath` 含 `B/target/classes`,不含 store 里的 B jar。
2. B 未构建 → A 的 `build` 报 `run mjm build in <path> first`,exit 1。
3. B 的 `target/classes` 版本戳与 `mjm.toml` 不一致 → 报错(旧产物)。
4. 版本不等且未开 skew → 报错;开了 skew → 成功 + 每次一行警告;本地版本更低 → 仍报错。
5. 根目录 `build` 按拓扑序(声明顺序颠倒也一样);成环 → 报错并列出环。
6. `package --uber` / `bundle` / `publish` 在替换命中时**拒绝**,消息含模块路径与三种解法。
7. `tree` / `info` 对命中的行显示 `(local: <path>)` 标记。
8. 根目录 `add` / `run` / `publish` → 拒绝;`--module` 可用。
9. 无 `mjm.work.toml` 时,所有命令的 lock 与 classpath 输出与今天**逐字节一致**。
10. `test` 在根目录:一个模块失败,其余仍跑完,汇总里能看出哪个失败。
