# REMINDS.md — 已知取舍与技术债

记录**刻意做的取舍**和**暂时容忍的债**：不是 bug（bug 该修），而是"现在这样做是对的，
但哪天条件变了就该重新决定"的事情。每条都写清楚现状、代价、触发条件。

---

## 1. `mjm bundle` 靠包名猜 toolchain

**位置**:`BundleCommand.isTestOnly(String id)`

```java
private static boolean isTestOnly(String id) {
    return id.startsWith("org.junit.") || id.startsWith("org.opentest4j:")
            || id.startsWith("org.apiguardian:");
}
```

**现状**:dist 把闭包分成两堆——toolchain（`org.junit.*` / `org.opentest4j:` /
`org.apiguardian:`,装进 `lib/toolchain/`）和骨架（其余,合并进 uber-jar）。判据是
**硬编码的包名前缀**。（`apiguardian` 是 junit 自己 API 注解所在的 jar,2026-11-22
补进 vendor 时一并加了这一条,否则它会被当成骨架合进 launcher 的 jar。）

**为什么现在可接受**:mjm 自己的 `mjm.toml` 里 junit 就是全部测试依赖,规则精确命中,
零误判。`mjm` 是当前唯一用 `mjm bundle` 的项目。

**代价/风险**:
- 任何别的项目依赖 junit,跑 `mjm bundle` 也会被塞进 `lib/toolchain/` 而不是 uber-jar
- 如果将来 mjm 引入非 junit 的测试库（assertj、mockito、testcontainers…），它们会被
  误判成骨架合并进运行 jar——**测试库进生产产物**,而且没人会立刻发现（因为它仍然能跑）

**触发重新决定的条件**（任一即可）:
- 第二个项目开始用 `mjm bundle`
- mjm 引入 junit 以外的测试依赖
- 有人问"为什么我的 assertj 在 lib/toolchain 里/在 uber-jar 里"

**正确的解法**:不要在 `BundleCommand` 里猜。让 `mjm.toml` 显式声明哪些是 toolchain,例如:

```toml
[toolchain]
"org.junit.jupiter:junit-jupiter-api" = "6.1.3"
"org.junit.platform:junit-platform-launcher" = "6.1.3"
```

然后 dist 按这份显式清单分流,`[deps]` 里的其余一切按骨架处理。这与 mjm 的
"closed schema, 不猜"气质一致——反过来,靠包名前缀猜正是 mjm 在别处明确拒绝的做法。

**成本**:中等。要动 SPEC（新增 `[toolchain]` 节）、ProjectReader、BundleCommand,
以及 mjm 自己的 mjm.toml 迁移。等触发条件出现再做,现在做是过早抽象。

---

## 2. 自举链的"滞后一版"陷阱（已修,但值得记住）

**曾经的症状**:改了 `dist` 的布局,v0.1.4 发布出来的附件**仍是旧布局**。

**原因**:CI 装的是**上一个 release** 的 mjm,然后用它跑 `mjm bundle`。所以任何对 dist
本身的修改,要等两个版本才生效——v0.1.4 的附件由 v0.1.3 打包,v0.1.3 不懂新布局。

**现在的做法**(`.github/workflows/ci.yml`):

```yaml
- name: dist (with the freshly built mjm)
  run: |
    CP="target/classes:$(find vendor -name '*.jar' | tr '\n' ':')"
    java -cp "$CP" com.jujin.mjm.cli.Mj dist
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

## 5. 未实现但已被文档误标过的命令

`DESIGN.md §8` 曾把 `mjm init` / `mjm remove` 标为 ✅ 但实际不存在（已纠正）。
剩下**真实未实现**的:

- `mjm clean` —— 目前只能 `rm -rf target/`
- `mjm outdated` —— 列出已锁依赖的可用新版本
- JUnit XML 测试报告 —— CI 的 checks UI 需要

这些在 `DESIGN.md §13` 的吸收清单里,不是债,是待办。

---

## 6. `publish` 与 `release` 的命名歧义

**位置**:`PublishCommand`(原 `ReleaseCommand` 已删除)

**现状**:两个词在英文里几乎同义,但在 mjm 里指两件不同的事:

| 命令 | 做什么 | 产出去哪 |
|---|---|---|
| `mjm publish [gh:…]` | 只发**库**的三件套(jar + 描述符 + sha256) | `dist/`,上传到 Release |
| `mjm release [--publish …]` | 跑**全流程** lock → build → verify → test → dist,可选地顺带 publish | `target/`(发行目录 + tar.gz),以及 `dist/` |

**实际关系**:`release` 的图里有一个 `publish` 阶段(默认关闭,`--publish`
才开),它调用的就是 `PublishCommand` 本身。所以 `release --publish X` **同时**
产出可执行发行目录和库三件套——落在两个不同目录,给两种不同消费者。

**为什么让人困惑**:`mjm release --publish gh:acme/widget` 读起来像同义反复。
真实语义是"跑完整流水线,并在最后把库也发出去",但名字没有传达这个包含关系。

**为什么暂不改名**:
- `publish` 对齐业界惯例(npm publish、cargo publish),不该动
- `release` 在 mjm 的自举语境里已特指"发版这条链",`--publish` 旗标本身暗示了包含关系
- 改名要同时动命令、`mjm release` 的旗标、CI、README、RELEASE.md,收益是"少困惑一秒"

**已落地(2026-11-21)**:`mjm release` 已删除,`mjm bundle` 改名为 `mjm bundle`。理由见 §7。
下面保留原分析,作为"为什么最后选了删除而不是改名"的记录。

**当时考虑过的替代**:不动命令名,而是**让 `release` 的输出说清它产出了什么**。
现在 dist 阶段打印 `dist /path`,publish 阶段打印三件套路径,但没有任何一行说
"这两个是不同的东西,给不同的人用"。在 release 结束时加一段总结:

```
release v0.1.5 complete:
  tool distribution  target/mjm-0.1.5.tar.gz   (for `curl | bash` users)
  library triple     dist/mjm-0.1.5.{jar,mjm.toml,sha256}   (for `mjm add` consumers)
```

**触发重新决定的条件**:
- 有人(包括未来的我)在 README 或 issue 里把两者混为一谈
- `release` 增加更多可选阶段,使"它到底发了什么"更难从命令名推断
- 出现第三种发布目标(索引、镜像、签名仓库),`--publish` 的单值旗标撑不住时

**成本**:改名是低风险但低收益的机械改动;加输出总结是极小改动且直接消解困惑。
**后者优先**。

---

## 7. 命令面按"产物"收敛(2026-11-21 决策记录)

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
1. **删除 `mjm release`** —— 生命周期编排是项目 CI 的职责,不是构建工具的。
   mjm 提供单步机制(lock/build/test/bundle),CI 决定何时调用、如何发布。
2. **`mjm bundle` → `mjm bundle`** —— 名字说清产物:"组装一个可安装的发行版",
   面向"有用户要 `curl | bash` 的应用",库永远不需要它。
3. **移除 freeway-flow 依赖** —— 唯一的用户是 `release` 的图。CLI 回到朴素形态,
   少一个依赖。flow 编排仍留在 freeway 框架里供别人用。
4. **`publish` 不再自己打 jar** —— 它上传 `package` 的产物。之前两者写同一路径
   `dist/<name>-<version>.jar`,后者静默覆盖前者:先 `package --uber` 再 `publish`
   会上传一个不含依赖的 jar。**这是静默数据损坏,不是命名问题。**

**产物矩阵(现在只有一种产物语义:jar)**:

```
mjm package           → dist/<name>-<version>.jar   (库;可选 --uber)
mjm publish           → 读 dist/*.jar + 写描述符/sha256 → 上传
mjm bundle            → dist/<name>-<version>/ + .tar.gz   (应用发行版)
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

## 14. `mjm test` 的 classpath 仍含 processor 工件（2026-11-22 记录）

**位置**:`ClasspathAssembler`，`Mode.TEST` 与 `Mode.EXPORT_TEST`。

**现状**:测试 classpath（`mjm test`）把锁里**全部**工件放进去，包括 `scope = "processor"`；而给 IDE
的 `classpath --test`（`EXPORT_TEST`）明确排除 processor。两种行为都在 `ClasspathAssembler` 收拢
之前就存在，这次只把它们搬进同一个 seam，**没有改任何一个**。

**为什么先不动**:
- `mjm test` 的 classpath 只影响测试 JVM，processor 多一个 jar 不会让测试失败；`EXPORT_TEST` 的
  排除是给 IDE 看的（processor 是构建工具，不是 API）。
- 两边谁对需要一次单独的决定：测试 classpath 到底该不该与“构建工具”隔离？改它会让某些用
  processor 生成测试代码的项目突然少一个 jar。

**代价/风险**:
- 同一个概念（测试 classpath）有两个不同答案，读代码的人会以为其中一个是 bug。
- 若将来 `mjm test` 也排除 processor，依赖 processor 在测试期运行的项目会静默变化。

**触发重新决定的条件**（任一即可）:
- 有人报告“测试里能用、IDE 里不能用”或反之
- 引入 workspace 后需要统一 classpath 语义
- processor 从 `javac -processorpath` 改成别的机制

**正确的解法**:让两个模式共享同一个 scope 过滤策略（大概率先统一为“排除 processor”），并在
`ClasspathAssembler` 里用一个函数表达，而不是两处 `switch` 分支各写一遍。
