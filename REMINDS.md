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

**代价**:每次 `mj test` 全量重编测试类,没有增量。当前规模(145 测试)是秒级,
不值得做增量。如果哪天测试编译变成瓶颈,再考虑。

---

## 5. 未实现但已被文档误标过的命令

`DESIGN.md §8` 曾把 `mj init` / `mj remove` 标为 ✅ 但实际不存在（已纠正）。
剩下**真实未实现**的:

- `mj clean` —— 目前只能 `rm -rf target/`
- `mj outdated` —— 列出已锁依赖的可用新版本
- JUnit XML 测试报告 —— CI 的 checks UI 需要

这些在 `DESIGN.md §13` 的吸收清单里,不是债,是待办。
