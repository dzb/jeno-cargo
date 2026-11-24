# POM 迁移真机记录（3 个真实工程）

**基线**：jeno `0.2.17`（迁移前）与修复后的源码构建；JDK 25；2026-11-23。
**样本**：`git archive HEAD` 复制到 `/tmp/jeno-pom-migration/<name>`，原仓库未被修改。
**注意**：这是迁移工具的真机证据，不是 CI 门禁；网络只用于复制仓库（不含依赖下载）。

## 结论

| 工程 | 根 POM（packaging=pom + modules） | 子模块 | 修复前 | 修复后 |
|---|---|---|---|---|
| soloncode | 8 个 modules | `soloncode-cli` | 根被写成普通 jeno.toml（`name=soloncode version=dev`），modules 被忽略 | 根 **exit 1** 且不写文件，列出 8 个 modules；子模块 3 → 4 个直接依赖，局部 parent 的 `dependencyManagement` 生效 |
| point-bbs | 7 个 modules | `point-web` | 根被写成 `name=point-parent version=1.0.7` | 根 exit 1 且不写文件；子模块 0 → 3 个直接依赖（`${project.version}` 管理的兄弟模块终于解析） |
| solon-ai | 2 个 modules | `solon-ai-core` | 根被写成 `name=solon-ai-framework version=dev` | 根 exit 1 且不写文件；子模块 0 → 1 个直接依赖 |

**根目录现在不会静默压平**：pom-packaging + modules 时 `jeno init --pom` 返回 1，打印模块列表，建议
`jeno init --ws` 后逐模块迁移。

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
| 备注 | 3 个依赖版本是 `${project.version}`；`jansi/jline` 属性在远端 | 3 个 freeway 依赖是 `2.0.1-SNAPSHOT` → jeno 拒绝 snapshot | 项目版本 `4.2.0-SNAPSHOT`；多个依赖版本属性在远端 |

## 不可表达项（按实际触发）

| 项 | 原文/现象 | 判定 | 处理 |
|---|---|---|---|
| 远端 parent | `solon-parent:4.2.0-SNAPSHOT`，`<relativePath/>` 为空 | **已支持 Central（非快照）** | 快照不请求，note 说明；已发布的 parent 经 store 递归合并 |
| 本地 parent | `soloncode-parent` / `point-parent` / `solon-ai-parent` | **已修复** | `translate(Path)` 递归读取，合并 properties 与 DM，校验坐标 |
| `packaging=pom` + modules | 三个根 POM | **不应压平成项目** | exit 1 + 模块列表 + 建议 `jeno init --ws` |
| build plugins | `solon-maven-plugin`、assembly、notice、compiler、surefire | **不可表达** | note 列出插件名，建议在 jeno build 前/替代执行 |
| repositories | point-bbs 的私有 Maven 仓库 | **部分可表达** | note；HTTPS 仓库可手写 `[repos.<name>] type="maven"`，http/snapshot 不支持 |
| profiles | 本次样本未触发 | 不可表达 | 通用 note；改用 `[overrides]` 或环境专属 manifest |
| exclusions | 三个样本的直接依赖均有 | **不可表达** | note；jeno.toml 无 exclusion 语法，需改依赖或接受传递闭包 |
| provided | solon-ai-core 等 | 语义不同 | note；容器提供的编译契约，仅在需要编译时入 `[deps]` |
| snapshot 依赖 | point-web 的 `freeway-* 2.0.1-SNAPSHOT` | 设计拒绝 | note；pin 已发布版本 |
| unresolved property | `jansi.version`、`jline.version`、`snack4.version`、`jackson.version` | 远端可读时由 parent 提供；仍缺则不可表达 | 合并后仍缺的只记 note；手工填版本 |
| `${project.version}`（本地 parent DM） | point-web 的兄弟模块 | **已修复** | 读取每个模块时用其自身 version 解析 DM |
| test include/exclude | surefire 配置 | 不可表达 | 由 build plugins note 覆盖；jeno 用 `[test]` 过滤器手工重写 |

## 远端 parent（`1aa2837` 新增）

本地 parent 不存在时，`init --pom` 会把声明的 parent 从 Central 经 store 拉下来（同一个 `PomSource`），
递归处理它自己的 parent，parent-first 合并 properties 与 dependencyManagement；坐标不匹配、快照、
`${...}` 版本、循环、抓取/解析失败都只记 note，迁移仍然产出可审阅的文件。`--offline` 时 store 已有的
parent 照常命中，缺的只记 note，不联网。

**Spring Boot 真机证据**（`spring-boot-starter-parent:3.3.4`，synthetic child，Central）：

```
note: parent org.springframework.boot:spring-boot-starter-parent:3.3.4 read from Central
note: parent org.springframework.boot:spring-boot-dependencies:3.3.4 read from Central
note: BOM org.springframework:spring-framework-bom:6.1.13 read from Central
[deps]
    "org.springframework:spring-core" = "6.1.13"
    "org.slf4j:slf4j-api" = "2.0.16"
```

**BOM import 递归（`4496a8d`）**：`spring-framework-bom` 会被继续从 Central 拉取，递归它自己的
parent/import，并把其 managed 版本以 `putIfAbsent` 合入；现在 `spring-core` 得到 **6.1.13**，
`jeno lock` 解析出 3 个工件（spring-core、spring-jcl、slf4j-api）。`--offline` 在 store 预热后结果相同。

边界：只沿 Central 递归；私有 repository 的 BOM、local reactor BOM 不自动解析（note）；BOM 深度上限 8；
BOM 自己的 properties 只用于解析它自己，不泄漏成子项目的插值属性。

三个样本重跑（online，带 PomSource）：结果与仅本地 parent 时一致（soloncode-cli 4、point-web 3、
solon-ai-core 1 个直接依赖）；`solon-parent:4.2.0-SNAPSHOT` 得到明确 note：

```
note: parent org.noear:solon-parent:4.2.0-SNAPSHOT is a snapshot — Central lookup is skipped;
      use the released parent or copy the versions by hand
```

## 修复内容

`ce26430`（本地 parent / 多模块拒绝 / notes）、`1aa2837`（远端 parent）、BOM 递归 commit（fake PomSource 单测）：

- `PomMigration.translate(Path)`：递归读取本地 parent（缺省 `../pom.xml`；显式空 `<relativePath/>` 不本地查找），
  parent-first 合并 properties/dependencyManagement；校验目标文件坐标是否真是声明的 parent；缺失/循环均 note 后继续。
- `${project.version}` 在 parent DM 中按子模块自身 version 解析（point-web 的兄弟模块依赖由此恢复）。
- `packaging=pom` + modules → `Result.multiModuleRoot()`；`InitCommand` 不写 jeno.toml，exit 1 并列出 modules。
- 新增 note：build plugins、repositories、pluginRepositories、profiles、exclusions、非 jar packaging、模块列表、BOM import。
- `translate(Path, PomSource)`：远端 parent 经 Central 递归解析；快照/未解析版本不请求；循环、缺失、异常均 note；
  `translate(Path)` 保持“仅本地”行为。
- BOM import 递归：`scope=import`（type pom 或省略）经 PomSource 拉取，递归其 parent/嵌套 BOM，
  managed 以 putIfAbsent 合并；快照/${...}/缺失/异常/循环/深度>8 均 note；BOM properties 不泄漏。

测试（25，全部合成 POM/fake PomSource，无网络）：本地 parent 合并、缺失、循环、pom+modules、notes、
`${project.version}`、init 多模块拒绝；远端 parent 合并、链式、缺失、异常/offline、循环、快照、未解析版本、
本地优先；BOM 直接 import、嵌套 import、BOM parent、缺失、异常/offline、循环、快照、未解析版本、
显式条目优先、深度上限、本地-only note。

## 迁移建议

1. 多模块根：`jeno init --ws` 生成 `jeno.ws.toml`，然后对每个要迁移的模块跑 `jeno init --pom`；根 POM 不要生成 jeno.toml。
2. 子模块：先确认输出里的 `parent … read from …` 是否指向正确的本地文件；远端 parent 贡献的属性必须手工补齐。
3. 清理 note 列出的 build plugins/exclusions/profiles/repositories；snapshot 依赖改为已发布版本。
4. 只有 `[deps]`/`[deps-dev]` 的简单模块通常可直接 `jeno lock`；复杂模块先按 note 修 manifest。

## 复现

```bash
mkdir -p /tmp/jeno-pom-migration
for name in soloncode point-bbs solon-ai; do
  mkdir -p /tmp/jeno-pom-migration/$name
  git -C /home/dzb/Projects/$name archive HEAD | tar -x -C /tmp/jeno-pom-migration/$name
done

# 根（修复后）：exit 1，列出 modules，不写 jeno.toml
cd /tmp/jeno-pom-migration/soloncode && ~/.jeno/bin/jeno init --pom

# 子模块（用修复后的构建）
CP="/path/to/jeno-cargo/target/classes:$(find /path/to/jeno-cargo/vendor -name '*.jar' | tr '\n' ':')"
cd /tmp/jeno-pom-migration/point-bbs/point-web
java -cp "$CP" com.jujin.jeno.cli.Jeno init --pom
cat jeno.toml
```
