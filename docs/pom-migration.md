# POM 迁移真机记录（3 个真实工程）

**基线**：mjm `0.2.17`（迁移前）与修复后的源码构建；JDK 25；2026-11-23。
**样本**：`git archive HEAD` 复制到 `/tmp/mjm-pom-migration/<name>`，原仓库未被修改。
**注意**：这是迁移工具的真机证据，不是 CI 门禁；网络只用于复制仓库（不含依赖下载）。

## 结论

| 工程 | 根 POM（packaging=pom + modules） | 子模块 | 修复前 | 修复后 |
|---|---|---|---|---|
| soloncode | 8 个 modules | `soloncode-cli` | 根被写成普通 mjm.toml（`name=soloncode version=dev`），modules 被忽略 | 根 **exit 1** 且不写文件，列出 8 个 modules；子模块 3 → 4 个直接依赖，局部 parent 的 `dependencyManagement` 生效 |
| point-bbs | 7 个 modules | `point-web` | 根被写成 `name=point-parent version=1.0.7` | 根 exit 1 且不写文件；子模块 0 → 3 个直接依赖（`${project.version}` 管理的兄弟模块终于解析） |
| solon-ai | 2 个 modules | `solon-ai-core` | 根被写成 `name=solon-ai-framework version=dev` | 根 exit 1 且不写文件；子模块 0 → 1 个直接依赖 |

**根目录现在不会静默压平**：pom-packaging + modules 时 `mjm init --pom` 返回 1，打印模块列表，建议
`mjm init --ws` 后逐模块迁移。

## 子模块迁移前后

| 子模块 | 修复前直接依赖 | 修复后直接依赖 | 修复前 unresolved-property note | 修复后 |
|---|---|---|---|---|
| `soloncode-cli` | 3 | 4（新增 `com.vladsch.flexmark:flexmark 0.50.50`） | 3 | 2（jansi/jline 仍在远端 parent） |
| `point-web` | 0 | 3（`point-domain 1.0.7`、`point-service 1.0.7`、`slf4j-api 2.0.18`） | 0 | 0 |
| `solon-ai-core` | 0 | 1（`com.knuddels:jtokkit 1.1.0`） | 3 | 2（snack4/jackson 在远端 parent） |

修复后 `soloncode-cli` 的关键 stdout：

```
note: parent org.noear:solon-parent:4.2.0-SNAPSHOT is not local — properties and
      dependencyManagement it contributed are not resolved; check versions by hand
note: parent org.noear:soloncode-parent:2026.11.11 read from …/soloncode-parent/pom.xml
note: build plugins are not migrated (org.noear:solon-maven-plugin, …:maven-assembly-plugin)
```

## 三个样本的 POM 特征

| 特征 | soloncode-cli | point-web | solon-ai-core |
|---|---|---|---|
| parent / relativePath | `soloncode-parent` via `../soloncode-parent/pom.xml`；它再指向远端 `solon-parent`（`<relativePath/>` 为空 → 不本地查找） | `point-parent`，无 relativePath → 默认 `../pom.xml` | `solon-ai-parent` via `../solon-ai-parent/pom.xml`；它再指向远端 `solon-parent` |
| properties | 部分在本地 parent（`flexmark.version`），部分只在远端（`jansi/jline`） | 本地 parent 定义 `slf4j.version` 等 | 本地 parent 定义 `jtokkit.version`；`snack4/jackson` 只在远端 |
| dependencyManagement / BOM | 本地 parent 的 DM 提供兄弟模块版本 | 本地 parent 的 DM 用 `${project.version}` 管兄弟模块 | 本地 parent 的 DM |
| dependencies scope | compile/test | compile/runtime/test，`provided` 出现 | compile/provided/test/optional |
| optional | 有 | 有 | 有 |
| exclusions | 有 | 有 | 有 |
| classifier/type | 无 | 无 | 无 |
| repositories / pluginRepositories | 无 | **有**（本地/私有 Maven 仓库） | 无 |
| profiles | 无 | 无 | 无 |
| packaging | jar | jar | jar |
| build plugins | `solon-maven-plugin`、`maven-assembly-plugin`（parent） | `maven-compiler-plugin`、`maven-surefire-plugin` | `notice-maven-plugin` |
| test include/exclude | surefire 默认 | surefire 配置（未迁移） | surefire 默认 |
| 备注 | 3 个依赖版本是 `${project.version}`；`jansi/jline` 属性在远端 | 3 个 freeway 依赖是 `2.0.1-SNAPSHOT` → mjm 拒绝 snapshot | 项目版本 `4.2.0-SNAPSHOT`；多个依赖版本属性在远端 |

## 不可表达项（按实际触发）

| 项 | 原文/现象 | 判定 | 处理 |
|---|---|---|---|
| 远端 parent | `solon-parent:4.2.0-SNAPSHOT`，`<relativePath/>` 为空 | **不应支持**（迁移不联网） | note 列出坐标；其 properties/DM 需手工补 |
| 本地 parent | `soloncode-parent` / `point-parent` / `solon-ai-parent` | **已修复** | `translate(Path)` 递归读取，合并 properties 与 DM，校验坐标 |
| `packaging=pom` + modules | 三个根 POM | **不应压平成项目** | exit 1 + 模块列表 + 建议 `mjm init --ws` |
| build plugins | `solon-maven-plugin`、assembly、notice、compiler、surefire | **不可表达** | note 列出插件名，建议在 mjm build 前/替代执行 |
| repositories | point-bbs 的私有 Maven 仓库 | **部分可表达** | note；HTTPS 仓库可手写 `[repos.<name>] type="maven"`，http/snapshot 不支持 |
| profiles | 本次样本未触发 | 不可表达 | 通用 note；改用 `[overrides]` 或环境专属 manifest |
| exclusions | 三个样本的直接依赖均有 | **不可表达** | note；mjm.toml 无 exclusion 语法，需改依赖或接受传递闭包 |
| provided | solon-ai-core 等 | 语义不同 | note；容器提供的编译契约，仅在需要编译时入 `[deps]` |
| snapshot 依赖 | point-web 的 `freeway-* 2.0.1-SNAPSHOT` | 设计拒绝 | note；pin 已发布版本 |
| unresolved property | `jansi.version`、`jline.version`、`snack4.version`、`jackson.version`（远端） | 不可表达 | note；手工填版本（mjm.toml 不插值） |
| `${project.version}`（本地 parent DM） | point-web 的兄弟模块 | **已修复** | 读取每个模块时用其自身 version 解析 DM |
| test include/exclude | surefire 配置 | 不可表达 | 由 build plugins note 覆盖；mjm 用 `[test]` 过滤器手工重写 |

## 修复内容

`ce26430`（代码 + 合成 POM 单测；不依赖网络）：

- `PomMigration.translate(Path)`：递归读取本地 parent（缺省 `../pom.xml`；显式空 `<relativePath/>` 不本地查找），
  parent-first 合并 properties/dependencyManagement；校验目标文件坐标是否真是声明的 parent；缺失/循环均 note 后继续。
- `${project.version}` 在 parent DM 中按子模块自身 version 解析（point-web 的兄弟模块依赖由此恢复）。
- `packaging=pom` + modules → `Result.multiModuleRoot()`；`InitCommand` 不写 mjm.toml，exit 1 并列出 modules。
- 新增 note：build plugins、repositories、pluginRepositories、profiles、exclusions、非 jar packaging、模块列表。

新增测试（6）：本地 parent 合并 properties/DM、parent 缺失、parent 循环、pom+modules 报告、plugins/repos/profiles/exclusions note、`${project.version}` 管理解析、`init --pom` 多模块拒绝。全部用合成 POM，无网络。

## 迁移建议

1. 多模块根：`mjm init --ws` 生成 `mjm.ws.toml`，然后对每个要迁移的模块跑 `mjm init --pom`；根 POM 不要生成 mjm.toml。
2. 子模块：先确认输出里的 `parent … read from …` 是否指向正确的本地文件；远端 parent 贡献的属性必须手工补齐。
3. 清理 note 列出的 build plugins/exclusions/profiles/repositories；snapshot 依赖改为已发布版本。
4. 只有 `[deps]`/`[dev-deps]` 的简单模块通常可直接 `mjm lock`；复杂模块先按 note 修 manifest。

## 复现

```bash
mkdir -p /tmp/mjm-pom-migration
for name in soloncode point-bbs solon-ai; do
  mkdir -p /tmp/mjm-pom-migration/$name
  git -C /home/dzb/Projects/$name archive HEAD | tar -x -C /tmp/mjm-pom-migration/$name
done

# 根（修复后）：exit 1，列出 modules，不写 mjm.toml
cd /tmp/mjm-pom-migration/soloncode && ~/.mjm/bin/mjm init --pom

# 子模块（用修复后的构建）
CP="/path/to/mjm-cargo/target/classes:$(find /path/to/mjm-cargo/vendor -name '*.jar' | tr '\n' ':')"
cd /tmp/mjm-pom-migration/point-bbs/point-web
java -cp "$CP" com.jujin.mjm.cli.Mjm init --pom
cat mjm.toml
```
