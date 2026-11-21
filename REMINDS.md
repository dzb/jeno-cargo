# REMINDS.md — 已知取舍与技术债

记录**刻意做的取舍**和**暂时容忍的债**：不是 bug（bug 该修），而是"现在这样做是对的，
但哪天条件变了就该重新决定"的事情。每条都写清楚现状、代价、触发条件。

---

## 1. `mj dist` 靠包名猜 toolchain

**位置**:`DistCommand.isTestOnly(String id)`

```java
private static boolean isTestOnly(String id) {
    return id.startsWith("org.junit.") || id.startsWith("org.opentest4j:");
}
```

**现状**:dist 把闭包分成两堆——toolchain（`org.junit.*` / `org.opentest4j:`,
装进 `lib/toolchain/`）和骨架（其余,合并进 uber-jar）。判据是**硬编码的包名前缀**。

**为什么现在可接受**:mj 自己的 `mj.toml` 里 junit 就是全部测试依赖,规则精确命中,
零误判。`mj` 是当前唯一用 `mj dist` 的项目。

**代价/风险**:
- 任何别的项目依赖 junit,跑 `mj dist` 也会被塞进 `lib/toolchain/` 而不是 uber-jar
- 如果将来 mj 引入非 junit 的测试库（assertj、mockito、testcontainers…），它们会被
  误判成骨架合并进运行 jar——**测试库进生产产物**,而且没人会立刻发现（因为它仍然能跑）

**触发重新决定的条件**（任一即可）:
- 第二个项目开始用 `mj dist`
- mj 引入 junit 以外的测试依赖
- 有人问"为什么我的 assertj 在 lib/toolchain 里/在 uber-jar 里"

**正确的解法**:不要在 `DistCommand` 里猜。让 `mj.toml` 显式声明哪些是 toolchain,例如:

```toml
[toolchain]
"org.junit.jupiter:junit-jupiter-api" = "6.1.3"
"org.junit.platform:junit-platform-launcher" = "6.1.3"
```

然后 dist 按这份显式清单分流,`[deps]` 里的其余一切按骨架处理。这与 mj 的
"closed schema, 不猜"气质一致——反过来,靠包名前缀猜正是 mj 在别处明确拒绝的做法。

**成本**:中等。要动 SPEC（新增 `[toolchain]` 节）、ProjectReader、DistCommand,
以及 mj 自己的 mj.toml 迁移。等触发条件出现再做,现在做是过早抽象。

---

## 2. 自举链的"滞后一版"陷阱（已修,但值得记住）

**曾经的症状**:改了 `dist` 的布局,v0.1.4 发布出来的附件**仍是旧布局**。

**原因**:CI 装的是**上一个 release** 的 mj,然后用它跑 `mj dist`。所以任何对 dist
本身的修改,要等两个版本才生效——v0.1.4 的附件由 v0.1.3 打包,v0.1.3 不懂新布局。

**现在的做法**(`.github/workflows/ci.yml`):

```yaml
- name: dist (with the freshly built mj)
  run: |
    CP="target/classes:$(find vendor -name '*.jar' | tr '\n' ':')"
    java -cp "$CP" com.jujin.mj.cli.Mj dist
```

**原则**:已安装的旧 mj 只负责 `lock` 和 `test`(那里上一版确实是正确的工具——
它验证新代码能不能被旧代码消费);**打包必须用刚编译出的代码**,否则发布的产物
永远落后一版,而你自己在本地看不到这个问题(本地一直用新代码)。

**给后来者**:任何"改变产物形状"的改动(dist 布局、jar 内容、setup.sh 模板、
manifest 结构),都要检查 CI 里那步用的是不是新代码。`--version` 和测试都测不出
这种滞后,只有解开真实附件看目录结构才能发现。

---

## 3. 版本号只有一个声明点,但它依赖构建先写资源

**位置**:`Version.java` / `BuildCommand.writeVersionResource`

**现状**:`mj.toml [project].version` 是唯一人工声明。构建时写入
`META-INF/mj/version.properties` + jar manifest,v运行时读资源。

**已知边界**:
- 从**未经 mj build 的源码树**直接跑 `java -cp src...` → 读不到资源 → 报告 `dev`
- 这是刻意的:错的版本号比诚实的 `dev` 更糟

**为什么不再有回退常量**:v0.1.2 曾有一个 `BOOTSTRAP_FALLBACK` 作过渡脚手架
(v0.1.2 由不生成资源的 v0.1.1 编译),v0.1.3 已删除。**如果将来再次大改构建流程,
可能又需要一轮类似的脚手架**,删的时候记得它只能活一个版本周期。

---

## 4. `mj test` 会清空 `target/test-classes`

**位置**:`TestCommand.compile`

**原因**:不清的话,删掉的测试类会留在磁盘上,fork JVM 扫描时抛
`NoClassDefFoundError`——而且只有删过测试类的人会踩到,本地很可能复现不了。

**代价**:每次 `mj test` 全量重编测试类,没有增量。当前规模下是秒级,
不值得做增量。如果哪天测试编译变成瓶颈,再考虑。

---

## 5. 未实现但已被文档误标过的命令

`DESIGN.md §8` 曾把 `mj init` / `mj remove` 标为 ✅ 但实际不存在（已纠正）。
剩下**真实未实现**的:

- `mj clean` —— 目前只能 `rm -rf target/`
- `mj outdated` —— 列出已锁依赖的可用新版本
- JUnit XML 测试报告 —— CI 的 checks UI 需要

这些在 `DESIGN.md §13` 的吸收清单里,不是债,是待办。

---

## 6. `publish` 与 `release` 的命名歧义

**位置**:`PublishCommand` / `ReleaseCommand`

**现状**:两个词在英文里几乎同义,但在 mj 里指两件不同的事:

| 命令 | 做什么 | 产出去哪 |
|---|---|---|
| `mj publish [gh:…]` | 只发**库**的三件套(jar + 描述符 + sha256) | `dist/`,上传到 Release |
| `mj release [--publish …]` | 跑**全流程** lock → build → verify → test → dist,可选地顺带 publish | `target/`(发行目录 + tar.gz),以及 `dist/` |

**实际关系**:`release` 的图里有一个 `publish` 阶段(默认关闭,`--publish`
才开),它调用的就是 `PublishCommand` 本身。所以 `release --publish X` **同时**
产出可执行发行目录和库三件套——落在两个不同目录,给两种不同消费者。

**为什么让人困惑**:`mj release --publish gh:acme/widget` 读起来像同义反复。
真实语义是"跑完整流水线,并在最后把库也发出去",但名字没有传达这个包含关系。

**为什么暂不改名**:
- `publish` 对齐业界惯例(npm publish、cargo publish),不该动
- `release` 在 mj 的自举语境里已特指"发版这条链",`--publish` 旗标本身暗示了包含关系
- 改名要同时动命令、`mj release` 的旗标、CI、README、RELEASE.md,收益是"少困惑一秒"

**已落地(2026-11-21)**:`mj release` 已删除,`mj dist` 改名为 `mj bundle`。理由见 §7。
下面保留原分析,作为"为什么最后选了删除而不是改名"的记录。

**当时考虑过的替代**:不动命令名,而是**让 `release` 的输出说清它产出了什么**。
现在 dist 阶段打印 `dist /path`,publish 阶段打印三件套路径,但没有任何一行说
"这两个是不同的东西,给不同的人用"。在 release 结束时加一段总结:

```
release v0.1.5 complete:
  tool distribution  target/mj-0.1.5.tar.gz   (for `curl | bash` users)
  library triple     dist/mj-0.1.5.{jar,mj.toml,sha256}   (for `mj add` consumers)
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

**四家里没有一家有 `release` 或 `dist` 命令。** `release` 是 mj 自造的概念。
`uv` 的 `publish` 默认值就是 `dist/*`——和 mj 的 `dist/` 布局一致。

**决定**:
1. **删除 `mj release`** —— 生命周期编排是项目 CI 的职责,不是构建工具的。
   mj 提供单步机制(lock/build/test/bundle),CI 决定何时调用、如何发布。
2. **`mj dist` → `mj bundle`** —— 名字说清产物:"组装一个可安装的发行版",
   面向"有用户要 `curl | bash` 的应用",库永远不需要它。
3. **移除 freeway-flow 依赖** —— 唯一的用户是 `release` 的图。CLI 回到朴素形态,
   少一个依赖。flow 编排仍留在 freeway 框架里供别人用。
4. **`publish` 不再自己打 jar** —— 它上传 `package` 的产物。之前两者写同一路径
   `dist/<name>-<version>.jar`,后者静默覆盖前者:先 `package --uber` 再 `publish`
   会上传一个不含依赖的 jar。**这是静默数据损坏,不是命名问题。**

**产物矩阵(现在只有一种产物语义:jar)**:

```
mj package           → dist/<name>-<version>.jar   (库;可选 --uber)
mj publish           → 读 dist/*.jar + 写描述符/sha256 → 上传
mj bundle            → dist/<name>-<version>/ + .tar.gz   (应用发行版)
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
