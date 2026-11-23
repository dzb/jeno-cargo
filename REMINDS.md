# REMINDS.md — 已知取舍与技术债

记录**刻意做的取舍**和**暂时容忍的债**：不是 bug（bug 该修），而是"现在这样做是对的，
但哪天条件变了就该重新决定"的事情。每条都写清楚现状、代价、触发条件。

---

## 1. toolchain 不再靠包名猜(已解决)

**当前位置**:`BundleCommand` 读取 `mjm.toml [toolchain].jars`

**旧问题**:dist 曾把闭包按 `org.junit.*` / `org.opentest4j:` / `org.apiguardian:`
包名前缀分成 toolchain 和骨架。任何非 JUnit 测试库都可能被误合进生产 jar,而恰好
同前缀的普通库也会被塞进 `lib/toolchain/`。

**现行规则**:`[toolchain].jars` 显式列出 artifact id。它们是 packaging metadata,
不参与解析、可见性或锁作用域;`bundle` 永不把它们合并进 launcher jar,
`bundle --toolchain` 才把它们复制到 `lib/toolchain/`。id 不在锁图中时 `bundle` 直接报错。
mjm 自己的 manifest 用这份清单标记 JUnit 工件;JUnit 仍是 MAIN 依赖,因为
`MjmTestFork` 属于生产 CLI 的编译面。

**代价**:清单需要包含 toolchain 闭包中的所有 id,漏写会让该 jar 被当成运行依赖或
从发行版中缺失。mjm 自己已经列出 JUnit/Platform/OpenTest4J/APIGuardian 全部 7 件。

---

## 2. 自举链的"滞后一版"陷阱（已修,但值得记住）

**曾经的症状**:改了 `dist` 的布局,v0.1.4 发布出来的附件**仍是旧布局**。

**原因**:CI 装的是**上一个 release** 的 mjm,然后用它跑 `mjm bundle`。所以任何对 dist
本身的修改,要等两个版本才生效——v0.1.4 的附件由 v0.1.3 打包,v0.1.3 不懂新布局。

**现在的做法**(`.github/workflows/ci.yml`):

```yaml
- name: bundle (with the freshly built mjm)
  run: |
    CP="target/classes:$(find vendor -name '*.jar' | tr '\n' ':')"
    java -cp "$CP" com.jujin.mjm.cli.Mjm bundle --toolchain
```

**原则**:已安装的旧 mjm 只负责 `lock` 和 `test`(那里上一版确实是正确的工具——
它验证新代码能不能被旧代码消费);**打包必须用刚编译出的代码**,否则发布的产物
永远落后一版,而你自己在本地看不到这个问题(本地一直用新代码)。

**给后来者**:任何"改变产物形状"的改动(dist 布局、jar 内容、setup.sh 模板、
manifest 结构),都要检查 CI 里那步用的是不是新代码。`--version` 和测试都测不出
这种滞后,只有解开真实附件看目录结构才能发现。

---

## 3. 版本号只有一个声明点,但它依赖构建先写资源

**位置**:`Version.java` / `BuildCommand.writeVersionResource`

**现状**:`mjm.toml [project].version` 是唯一人工声明。构建时写入
`META-INF/mjm/version.properties` + jar manifest,v运行时读资源。

**已知边界**:
- 从**未经 mjm build 的源码树**直接跑 `java -cp src...` → 读不到资源 → 报告 `dev`
- 这是刻意的:错的版本号比诚实的 `dev` 更糟

**为什么不再有回退常量**:v0.1.2 曾有一个 `BOOTSTRAP_FALLBACK` 作过渡脚手架
(v0.1.2 由不生成资源的 v0.1.1 编译),v0.1.3 已删除。**如果将来再次大改构建流程,
可能又需要一轮类似的脚手架**,删的时候记得它只能活一个版本周期。

---

## 4. `mjm test` 会清空 `target/test-classes`

**位置**:`TestCommand.compile`

**原因**:不清的话,删掉的测试类会留在磁盘上,fork JVM 扫描时抛
`NoClassDefFoundError`——而且只有删过测试类的人会踩到,本地很可能复现不了。

**代价**:每次 `mjm test` 全量重编测试类,没有增量。当前规模下是秒级,
不值得做增量。如果哪天测试编译变成瓶颈,再考虑。

---

## 5. 曾未实现、现已交付的命令(历史记录)

这条记录保留的是文档跑在代码前面的教训,不是当前功能清单。

| 当时的待办 | 现在 |
|---|---|
| `mjm clean` | 已实现:`clean --target`(默认)与 `--dist`,永不触碰 store |
| `mjm outdated` | 已实现:只读锁文件,支持 `--json` / `--dev` |
| JUnit XML 测试报告 | 已实现:`target/test-results/TEST-*.xml`,64 个 suite 由测试覆盖 |

教训仍是:文档没有可执行测试,就必须在功能落地时同步改掉旧表述。

---

## 6. `publish` 与 `release` 的分工

**当前位置**:`PublishCommand` / `ReleaseCommand`

两个词在 mjm 里有明确不同的产物:

| 命令 | 做什么 | 产出去哪 |
|---|---|---|
| `mjm publish [target]` | 发**库**的三件套:jar + `.mjm.toml` + `.sha256`;不上传自己构建的 jar | `target/dist/`;给定目标时上传并回读校验 |
| `mjm release [target]` | 发**应用安装包**:`bundle` 产生的 tar.gz + bootstrap setup;默认 minisign 签名 | `target/dist/release/`;给定目标时上传并回读校验 |

历史歧义来自旧的 `release --publish` 全流程命令:它同时做构建、测试、bundle 和上传,
名字却只说了发布。v0.2.17 之后这个生命周期命令已删除;现行 `release` 是单步上传器,
**不会隐式 build/test/bundle**。决定何时执行这些步骤的是项目 CI。

因此现在的边界是“产物语义”而不是“流水线阶段”:

- 有依赖解析消费者 → `publish` 库三件套;
- 有 `curl | bash` 最终用户 → `release` 应用安装包;
- 两者都不替你决定构建或发版时机。

---

## 7. 命令面按"产物"收敛(2026-11-21 历史决策)

> **历史记录**:本节的“删除 `mjm release`”在 v0.2.17 被更新。现行契约见 SPEC §6:
> `publish` 发库三件套,`release` 只上传已 bundle 的安装包;两者都是单步命令,
> 都不编排完整生命周期。下面的旧决策保留用于解释当时的取舍。

**背景**:`publish` / `dist` / `release` 三者职责重叠,人和 agent 都难以抉择。

**调研结论**(本机实测 `go` / `npm` / `uv` 的真实命令面):

| | go | cargo | npm | uv |
|---|---|---|---|---|
| 有 publish 吗 | ❌ **没有** | ✅ | ✅ | ✅ |
| "发布"实际是什么 | `git tag` + 推仓库 | 上传 crate | 上传 tarball | 上传 wheel/sdist |
| 有 release/dist 命令吗 | ❌ | ❌ | ❌ | ❌ |
| 构建与发布分离 | N/A | ✅ | ⚠️ | ✅(publish 默认读 `dist/*`) |

**四家里没有一家有 `release` 或 `dist` 命令。** `release` 是 mjm 自造的概念。
`uv` 的 `publish` 默认值就是 `dist/*`——和 mjm 的 `dist/` 布局一致。

**决定**:
1. **删除旧的 `mjm release` 生命周期命令** —— 生命周期编排是项目 CI 的职责,不是构建工具的。
   v0.2.17 重新引入了同名但语义更窄的单步上传器;它只发已 bundle 的安装包。
2. **`mjm bundle` → `mjm bundle`** —— 名字说清产物:"组装一个可安装的发行版",
   面向"有用户要 `curl | bash` 的应用",库永远不需要它。
3. **移除 freeway-flow 依赖** —— 唯一的用户是 `release` 的图。CLI 回到朴素形态,
   少一个依赖。flow 编排仍留在 freeway 框架里供别人用。
4. **`publish` 不再自己打 jar** —— 它上传 `package` 的产物。之前两者写同一路径
   `dist/<name>-<version>.jar`,后者静默覆盖前者:先 `package --uber` 再 `publish`
   会上传一个不含依赖的 jar。**这是静默数据损坏,不是命名问题。**

**产物矩阵(现在只有一种产物语义:jar)**:

```
mjm package           → target/dist/<name>-<version>.jar   (库;可选 --uber)
mjm publish           → 读 target/dist/*.jar + 写描述符/sha256 → 上传
mjm bundle            → target/dist/<name>-<version>/ + .tar.gz   (应用发行版)
```

**留给后来者**:如果再次出现"要不要加一条流水线命令"的念头,先问一句
"这件事 CI 能不能自己串起来?"。能,就不要进 CLI。

---

## 8. 发版纪律:commit ≠ release(2026-11-21 事故记录)

**事故**:一天之内打了 10 个 tag(v0.1.0 → v0.1.9),其中 **5 个只隔 1 个 commit**。
release 页变成流水账,版本号失去意义。

**根因**:开发命令写成了

```bash
git commit ... && git push ... && git tag vX && git push --tags
```

`&& git tag` 被缝进了每次提交里——**规则是"只在打 tag 时发版",而实际上每次提交都在打 tag**。
问题不在规则,在于执行动作把规则绕过去了。

**规则**:

- **日常改动**:`commit` + `push master`。CI 会跑 lock/build/test,**不发版**。
- **打 tag**:一个独立、有意识的决定。只在"这一批改动构成一个完整版本"时做。
- 一版应当包含**一批**改动,而不是一项。如果一个 tag 只隔 1–2 个 commit,
  这个 tag 大概率不该存在。

**判断标准**:如果 release notes 写不出比"fix: 某处小问题"更有内容的一行,
就不该发版。

**触发重估的条件**:无。这是纪律,不是权衡。发布节奏可以被业务需求改变
(如需要尽快交付某修复),但"每次提交都发版"永远不是答案。

**事故后的处置**:v0.1.2–v0.1.9 的 release 与 tag 全部撤销,全部改动重切成
**单个 v0.1.2**。版本史回到可读状态。

---

## 9. 自举构建的 javac 警告（2026-11-22 记录，同日已解决）

**位置**:`mjm build`（编译 `src/main/java`）的输出；来源是 vendor 里的 junit jar。

**结局（2026-11-22）**:已解决，`mjm build` 现在是零警告。做法是给 `vendor/` 补上真正的
`org.apiguardian:apiguardian-api:1.1.2`（jar + 无 parent 链的独立 pom），并在 5 个 junit 件的
独立 pom 里声明它——它们本来就该有这条依赖，裁剪时漏了。同时 mjm 自己的 `mjm.toml` 也把
它列成直接依赖：严格可见性只把直接声明放进 compile classpath，传递依赖是看不见的，所以
"让警告消失"和"遵守自己的可见性规则"是同一个动作。

顺手暴露并修掉的另一件事：`local:` 仓库的文件改了，`mjm lock` 却读不到——store 的
URL→digest 索引把旧 pom 缓存住了。现在在线时 `file:` 的 URL 不再走索引（本地文件是工作副本，
本来就会改），离线时仍然查索引（跨目录的 store 命中靠它，SPEC §1.5）。

**原来的记录（保留作历史）**:`mjm build` 每次刷 87 条
`警告: 未知的枚举常量 Status.MAINTAINED` / `Status.STABLE` / …，
首条带原因:`找不到 org.apiguardian.api.API$Status 的类文件`。编译成功、产物无异常，
只是输出噪音——`mjm test` 与 `mjm build` 的数字（87）因此不能当作"零警告"。

**成因**:junit 的 class 文件带 `@API(...)` 注解，注解的枚举来自
`org.apiguardian.api`。`vendor/` 是手工裁剪的 Maven 布局仓库（只有 2 个 freeway 件(commons/ioc)+ `slf4j-api` + 6 个 junit 件的 pom/jar，pom 是无 parent 链的独立清单），**没有 `org.apiguardian.api` 的
jar 也没有它的 pom**。javac 在编译消费者的源码时看到注解里引用了找不到的枚举，就逐处警告。

**为什么现在可接受**:
- 是 `vendor/` 的内容问题，不是代码问题；生产构建（不编译 junit 消费者）不会出现。
- 不影响字节、不影响测试、不影响发布；`mjm` 是当前唯一受影响的构建。

**代价/风险**:
- 87 条噪音盖住真正的警告——新增一条编译警告时没人会看见。
- 任何"构建零警告"的 CI 门禁都没法开。

**触发重新决定的条件**（任一即可）:
- 有人开始给构建加"零警告"门禁，或需要从输出里分辨真实警告
- junit 版本升级后把 apiguardian 变成必需依赖（届时 vendor 必须补它，否则 lock 解析就会缺件）
- 有用户把这段输出当成构建错误上报

**正确的解法**:把 `org.apiguardian:apiguardian-api` 的 jar 与 pom 一起放进 `vendor/`
（sha256 由 `mjm lock` 钉住），补齐闭包——这比在构建参数里压警告更符合"vendor 是一个仓库"
的定位。**不要**为了让警告消失而把 `@API` 注解从 vendor 的 jar 里删掉（那就动了别人的字节）。

---

## 10. `SchemaSource` 的两个位置缺口（2026-11-22 记录）

**位置**:`src/main/java/com/jujin/mjm/project/SchemaSource.java`（schema 报错的 `文件:行:列` 来源）。

**现状**:它用一次轻量扫描为 `ProjectReader`/`DescriptorReader` 的错误找位置，但有两类合法 TOML
没覆盖：

1. **带转义的 quoted key**：`[project]` 下写 `"mi\u0061n" = 1`（TOML 解成 `mian`）。
   扫描器只反转义 `\"` 与 `\\`，不认 `\uXXXX`，于是记录的是 `mi\u0061n`，
   与解析器给出的 `mian` 对不上，查不到位置 → 报错退化成 `mjm.toml: [project].mian is not a key`。
2. **`[[artifacts]]` 这类数组表**：扫描器把 `[` 后第一个 `]` 当节名结尾，
   得到 `[artifacts`。在非 lock 文件（mjm.toml / 描述符）里它本来就会触发
   `must be a table` 的解析错误，这条错误没有位置。

**为什么现在可接受**:
- 两者都只影响**报错信息的质量**（少一个 `行:列`），不影响解析正确性：键值仍由 TOML 解析器
  决定，扫描器只提供坐标。
- 转义 key 是合法但极罕见的写法（正常键名 `mian` 直接写即可）；`[[artifacts]]` 是锁文件的内部
  语法，mjm.toml 里出现它属于误用。
- 常规 quoted key（`"group:artifact"`、`"mi=an"`）与 quoted 节名（`["dep"]`）已经带位置，有测试。

**代价/风险**:
- 如果 D12 的“带行号/路径的明确报错”被当成对外承诺，这两条就是承诺里的两个洞：
  用户拿到一条没有位置的消息时，会先怀疑是自己写错了文件。
- 将来若把 TOML 子集放宽（数组表、点键），扫描器会先于解析器失配。

**触发重新决定的条件**（任一即可）:
- 有人把“每个 schema 错误都带 `文件:行:列`”写进对外文档或验收标准
- 用户反馈过一条没有位置的 schema 报错
- 扫描器需要覆盖新的 TOML 构造（数组表、点键、多行字符串）

**正确的解法**:不要把“找位置”做成第二次手写扫描。两条路：
① 让 `TomlUtils` 在解析时把每个键的位置一起返回（解析器本来就知道自己在第几行第几列），
`SchemaSource` 退化成一张查询表；② 若要保留独立扫描，用与解析器同一套字符串解码
（含 `\uXXXX`）并识别 `[[...]]` 形式，且为扫描器补上“位置缺失即测试失败”的守卫。

---

## 11. 项目锁文件不回收（2026-11-22 记录）— **已解决（`mjm cache prune`）**

**位置**:`ProjectLock`（`build`/`test`/`clean` 的项目级互斥，文件在 `~/.mjm/locks/<sha256(realpath)>.lock`）。

**现状（记录时的样子）**:每个被操作过的项目目录对应一个 0 字节的锁文件，用完不删。一个项目在不同路径下被克隆过几次
（`/tmp/junit-*` 这类一次性目录、临时 clone、测试 fixture），就会各留一个。清理之前本机累积了 492 个
（68 KB）——全是空文件，但 `ls` 会很吵。测试现在走临时锁目录，不再往真实 home 里写；已经存在的可以
直接 `rm -rf ~/.mjm/locks`（下次用到会重建）。

**为什么现在可接受**:
- 单用户、少项目时增长很慢（每次新目录一条，字节数为零）。
- 锁的正确性不依赖“目录里有谁”：文件被删掉只影响那一次等待，重建后照常工作。
- 有 GC 反而要判断“这个项目还会不会被用”，比空文件本身更贵。

**代价/风险**:
- 在 CI 或高并发机器上，一次性 clone 会持续留下空文件；`~/.mjm/locks` 的条目数只增不减。
- 同一个项目换路径（symlink / 重新 clone）会被当成两个项目，两条锁互不相干（这是有意的：它们确实是
  两个目录）。

**触发重新决定的条件**（任一即可）:
- 要做 daemon / 常驻进程，锁的生命周期跟着进程走
- 多人共享同一台机器（或共享 home）时，锁文件数量与命名需要交代清楚
- `~/.mjm/locks` 的条目数到了打扰人的量级（例如几万）

**正确的解法（已实施 2026-11-22）**:锁文件现在写入一行 `project=<canonical path>` 侧车（只在文件为空时写，
不重复改写）；`mjm cache prune` 删掉“项目目录已不存在”的锁、`part-*.tmp` 残留与悬空索引条目，
旧的无侧车空文件视为未知、保留。锁目录本身也迁到了 XDG state。

---

## 12. 已发布的旧二进制读不了新值形态（2026-11-22 记录）

**位置**:`mjm.toml` 的 `[deps]` 值形态（`"com.corp:lib" = "corp:2.1.0"`，SPEC §2.1）。

**现状**:本版引入“值里写仓库名”后，仓库自己的 manifest 也已经迁过去了。**在此之前发布的二进制**
（0.1.1 及更早）不认识这个形态：它把这串值当成 Central 版本，于是 `mjm lock` 会去 Central 找
`vendor:2.0.2` 并失败（实测：`unable to fetch the pom for …:vendor:2.0.2`）。`build`/`test` 还能跑
（它们只用 lock），但 `lock` 不行。

**为什么这样做**:语法人人可用之前先自己用，是唯一能发现“设计得好不好用”的办法；而旧二进制已经发布，
没有向前兼容的改法（它读的是旧代码）。

**代价/风险**:
- **升级不可逆**：一个工程迁到值形态后，不能再用旧二进制 `lock`。要回退，只能把 manifest 的依赖改回
  Central 版本 + 分区形式，再重锁。
- CI 若“装上一个 release，再对 push 上来的新树跑 lock/build/test”，则在包含该迁移的那一次 push 上，
  `lock` 会用旧二进制而失败。需要先发布包含本特性的版本（或让 CI 用从被推树构建的二进制）。

**触发重新决定的条件**（任一即可）:
- 有用户需要在两代二进制之间来回切（例如团队里有人暂时不升级）
- CI 的自举顺序无法保证“新树用新二进制”

**正确的解法**:不要为旧二进制加兼容垫片（它已发布）。可行的做法是发布说明里明确“这一版之后，
manifest 可以使用值形态；旧二进制不能 lock 新形态”，并在 CI 里把“安装的版本”和“被推的树”对齐
（例如用被推树的产物做 bootstrap）。

---

## 13. `add` / `remove` / `lock` 持项目锁（2026-11-22 记录）

**位置**:`ProjectLock`（`build`/`test`/`clean`/`add`/`remove`/`lock`/`package`/`bundle`/`publish` 共用的一把项目锁）。

**现状**:写 `mjm.toml`/`mjm.lock` 的三个命令现在也排队。`prepare` 会访问网络（取 pom/描述符）
并写 store，整段都在锁内，所以一个正在跑的 `add` 会让并发的 `build`/`test`/`clean` 等它。
写 `dist/` 的三个命令（`package`/`bundle`/`publish`）同样持锁；**`publish` 连上传阶段也在锁内**——
它上传读的就是 `dist/` 里的字节，只锁写盘阶段的话，`clean --dist` 仍可能在上传中途把 jar 抽走。

**为什么可接受**:三个写命令本来就秒级；排队换来的是“磁盘上永远是一个完整状态”，这比几个命令的
并发度重要。同 JVM 内用 `ReentrantLock` 先排队，跨进程用文件锁。

**代价/风险**:
- 网络慢时，一个 `add` 会阻塞该工程的 `build`/`clean`（哪怕它们完全不关心那个新依赖）。
- 锁按项目的 real path 命名：同目录的两个 symlink 路径算一个（正确），但两个不同克隆算两个。

**触发重新决定的条件**（任一即可）:
- 出现长时间运行的写命令（例如未来的 daemon、workspace 批量操作）
- 用户抱怨“`clean` 被一个卡住的 `add` 拖着”
- 需要把“取数”与“提交”拆到两把锁（读锁/写锁）时

**正确的解法**:把 `prepare` 的网络与 store 阶段移出项目锁，只在 `commit` 期间持锁（这需要保证
“prepare 之后 manifest 没被别人改过”——可以用内容哈希做乐观并发检查）；或引入读/写两把锁。

---

## 14. `mjm test` 的 classpath 与 processor（2026-11-23 已解决）

**位置**:`ClasspathAssembler`，`Mode.TEST` 与 `Mode.EXPORT_TEST`；`TestCommand`。

**曾经的状态**:测试 classpath（`mjm test`）把锁里全部工件放进去，包括 `scope = "processor"`；
`classpath --test`（`EXPORT_TEST`）排除 processor。同一个概念有两个答案。

**现在的行为**:两者都排除 processor。测试编译需要 processor 时，`TestCommand` 像 `build` 一样从
锁里收集 PROCESSOR 工件，经 `ClasspathAssembler.artifactPath` 解析（store 或 workspace），
放到 javac 的 `-processorpath` 上——processor 是编译工具，不是测试链接的 API。

**代价**:依赖 processor 在**运行期**（测试 JVM 里）被加载的项目，之前靠 classpath 里多出的 jar
工作，现在需要把该工件声明为普通依赖或 dev-dependency。

**何时重看**:若 annotation processor 退出 JDK 的 `-processorpath` 机制，或者测试运行期确实需要
processor 自身的类（而不是它生成的东西）。
