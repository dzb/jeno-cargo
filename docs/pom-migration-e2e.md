# init --pom 端到端记录：point-bbs / point-domain

**基线**：`mjm init --pom` 用修复后的 HEAD 构建（含 `[java]` 推导、本地/远端 parent、BOM import）；
`lock` / `build` / `test` / `package` 用 `~/.mjm/bin/mjm 0.2.17`；JDK 25；2026-11-23。
**副本**：`git -C ~/Projects/point-bbs archive HEAD | tar -x -C /tmp/…`；原仓库未修改。

## 项目

- `point-bbs/point-domain`，Maven 模块，**64 个源文件**。
- 本地 parent `point-parent:1.0.7`（`../pom.xml`）。
- 两个依赖是 `freeway-commons` / `freeway-db` 的 `2.0.1-SNAPSHOT`，mjm 拒绝 snapshot。

## init 输出

```
note: parent com.jujin.point:point-parent:1.0.7 read from /tmp/…/point-bbs/pom.xml
note: com.jujin8.freeway:freeway-commons is a snapshot — mjm rejects snapshots; pin a released version
note: com.jujin8.freeway:freeway-db is a snapshot — mjm rejects snapshots; pin a released version
created …/point-domain/mjm.toml (migrated from pom.xml)
```

生成的 `mjm.toml`（`[java]` 来自 parent 的 `maven.compiler.release`）：

```toml
[project]
name = "point-domain"
version = "1.0.7"
# migrated from Maven groupId com.jujin.point

[java]
version = 25
```

两个 snapshot 依赖被跳过，因此 `[deps]` 为空——这是迁移工具“不猜”的结果，不是解码失败。

## 迁移后人工审阅（手工补的三处）

1. **本地 mjm 仓库**（freeway 已在 `~/Projects/freeway/vendor` 用 `mjm publish mjm:local:` 发布）：
   ```toml
   [repos.freeway]
   type = "mjm"
   url = "/home/dzb/Projects/freeway/vendor"
   ```
2. **snapshot → 已发布版本**：
   ```toml
   [deps]
   "freeway-commons" = "freeway:2.0.2"
   "freeway-db" = "freeway:2.0.2"
   ```
3. **深度预算**：默认 `max-depth = 2` 在
   `freeway-db → freeway-ioc → slf4j-api` 处超限；这是策略选择，迁移工具不自动放宽：
   ```toml
   [limits]
   max-depth = 5
   ```

这三处都是**人工决策**（选择哪个本地发布渠道、钉哪个发布版本、接受多深的链），不是 POM 解码缺口。

## 结果

| 命令 | 结果 |
|---|---|
| `mjm lock` | **exit 0**，`locked 5 artifact(s)` |
| `mjm build` | **exit 0**，`compiled 64 source file(s)` |
| `mjm test` | **exit 0**，`no tests under …/src/test/java` |
| `mjm package` | **exit 0**，`target/dist/point-domain-1.0.7.jar`（91,947 bytes） |

完整日志：`logs/g244-point-domain-init.log`、`logs/g244-point-domain-e2e.log`。

## 结论

- 真实模块（64 源文件、本地 parent、快照依赖、自由深度链）可以完成 init → lock → build → test → package 全流程。
- `[java].version` 已从 Maven properties 自动推导（本样本为 25）。
- snapshot 替换、本地仓库声明与 `max-depth` 放宽属于迁移后的人工审阅项；工具不自动决定它们。
- 原仓库未被修改；`mjm.lock` 未变（本单没有改仓库自身的锁）。

## 复现

```bash
rm -rf /tmp/mjm-e2e && mkdir -p /tmp/mjm-e2e
git -C ~/Projects/point-bbs archive HEAD | tar -x -C /tmp/mjm-e2e
cd /tmp/mjm-e2e/point-bbs/point-domain

# HEAD 的 init（含 [java] 推导）
CP="/path/to/mjm-cargo/target/classes:$(find /path/to/mjm-cargo/vendor -name '*.jar' | tr '\n' ':')"
java -cp "$CP" com.jujin.mjm.cli.Mjm init --pom

# 按上面的三处手工调整 mjm.toml，然后：
~/.mjm/bin/mjm lock
~/.mjm/bin/mjm build
~/.mjm/bin/mjm test
~/.mjm/bin/mjm package
```
