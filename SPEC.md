# mj 文件格式规范(SPEC)

状态:草案 v0.1(2026-11-21,待评审)
配套:[DESIGN.md](DESIGN.md)(决策与理由)。本文只定义**文件的语法与语义**,是实现验收的依据。

总原则:**拒绝式解析**。任何未在本 spec 中定义的节、键、值形态、语法糖 → 立即报错。
schema 封闭,拼写错误(`[dep]` ≠ `[deps]`)不会静默失效。

---

## 0. 三个文件

| 文件 | 谁写 | 谁读 | 提交版本库 |
|---|---|---|---|
| `mj.toml` | 人 | mj | 是 |
| `{artifact}-{version}.mj.toml`(描述符) | `mj publish` 生成 | mj(消费方解析时) | 随发布物 |
| `mj.lock` | mj | mj | 是 |

---

## 1. TOML 子集文法

> 唯一实现:freeway-commons `com.jujin.freeway.commons.toml`(DESIGN D14)——mj.toml、描述符、mj.lock 与 freeway 的 `application.toml` 共用一份解析器。

```
document      = *( ws / comment / table / array-table / keyval )
comment       = "#" *( 任意非换行字符 )
table         = "[" dotted-key "]"                  ; [deps] [deps.corp] [limits] …
array-table   = "[[" dotted-key "]]"                ; 仅 mj.lock 使用
keyval        = key ws "=" ws value
key           = bare-key / quoted-string
bare-key      = 1*( A-Z / a-z / 0-9 / "_" / "-" )
quoted-string = 基础字符串(作键时容纳 "group:artifact")
value         = basic-string / integer / boolean / string-array
string-array  = "[" ws basic-string *( "," ws basic-string ) [","] ws "]"
```

支持与限制:

- 基础字符串转义:`\b \t \n \f \r \" \\ \uXXXX`
- **不支持**(出现即报错):多行字符串、字面字符串(`'...'`)、内联表、点键 keyval(`a.b = 1`)、日期/时间类型
- integer:十进制,64 位
- 同一 key 重复定义 → 报错;同一 key 同时出现在普通表与数组表 → 报错

---

## 2. mj.toml

### 2.1 节与字段(closed schema)

**[project]**

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `name` | 字符串 | 是 | `^[a-z][a-z0-9-]*$`(用作 mj 原生发布的 artifact 名) |
| `version` | 字符串 | 是 | mj 原生发布时必须 semver(§2.4) |

**[java]**

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `version` | 整数 | 否 | 目标 major 版本。编译:`javac --release N`;编译与运行要求当前 JDK major **>= N**(允许更高 JDK,字节码由 `--release` 保证)。缺省 = 不设限 |

> 注:mj 自身基于 Freeway(JDK 25+),运行 mj 需要一个 ≥25 的 JDK;用户项目可通过 `--release` 目标更低版本。

**[deps] 与 [deps.{repo}]**

key = 坐标,value 形态决定物种:

| 值形态 | 物种 | 要求 |
|---|---|---|
| `"1.2.3"` | central | 版本字符串非空、无空白;**禁止 version ranges**(`[1.0,2.0)`) |
| `"gh:owner/repo@tag"` / `"gitee:owner/repo@tag"` | mj 原生 | tag 为可选 `v` 前缀 + semver;展开规则见 §2.2 |
| `"https://…/x.jar"` | 裸 URL(叶子) | 必须 https、以 `.jar` 结尾 |

- 同一坐标同时出现在 `[deps]` 与任何 `[deps.{repo}]` → 报错
- `{repo}` 未在 `[repos]` 中声明 → 报错

**[repos]**

| 字段 | 规则 |
|---|---|
| key | 仓库名,`^[a-z][a-z0-9-]*$` |
| value | base:**https URL**,或 **`local:` 目录**——本地 Maven 布局目录(`~` 展开为 home,相对路径相对项目根),自举与离线通道 |

查找顺序:**互不回退**——`[deps.{repo}]` 只查对应仓库,`[deps]` 只查 Central。
`local:` 仓库的字节同样经过 store 的 sha256 钉定,与远程仓库同等可审计。

**[limits]**

| 字段 | 规则 |
|---|---|
| `max-depth` | 整数,可选,范围 1–255,**缺省 2**(默认即哲学)。0 与负数 → 报错 |

**[overrides]**

- key = 依赖 id(引号字符串:`group:artifact` 或 mj 原生 repo 名),value = 版本字符串
- central 物种:任意 Maven 版本字符串;mj 原生物种:必须 semver;URL 物种无版本概念 → 其 id 出现在此 → 报错
- 语义:MVS 之上的显式钉定——收敛时该 id 直接取 override 版本,忽略其他来源的版本约束;跨 major 硬冲突在 override 应用**之后**检查(它是合法出口)
- 解析后闭包中不存在该 id → 告警(提示清理残留声明)
- 被 override 的 id 必须在 `mj tree` / `mj why` 输出中标注
- value 为 version ranges → 报错(与 deps 一致)

**[scripts]**

- key = 任务名,value = 命令字符串
- 执行方式:经系统 shell(`sh -c` / `cmd /c`),与 npm scripts 一致
- `mj run <script-name>` 优先于 `<main-class>`:先查 [scripts],命中则执行脚本;未命中才走 java 主类路径

**[build]**

- key = `parameters`(唯一支持的键),value = 字符串数组,每项是追加到 javac 的旗标
- 用途:项目需要 `-parameters` 等编译器旗标时声明,freeway 系 BeanIntrospector 依赖参数名
- 缺省 = 空数组(javac 默认行为);未知键 → 报错

未知节、未知键 → 报错。

### 2.2 值形态展开(mj 原生)

```
gh:{owner}/{repo}@{tag}
  → https://github.com/{owner}/{repo}/releases/download/{tag}/{repo}-{version}.jar
  → 及同名 .mj.toml 与 .sha256

gitee:{owner}/{repo}@{tag}
  → https://gitee.com/{owner}/{repo}/releases/download/{tag}/{repo}-{version}.jar
```

`{version}` = tag 去 `v` 前缀。URL 模式待 Q8 实测确认(Gitee 侧)。

### 2.3 深度预算(语义,细节见 DESIGN.md §4.2)

- compile/runtime 闭包计入,test-scope 不计
- MVS 收敛后的最终图上计数;**深度 = 从声明根出发的最长引入路径**(预算的真意是暴露最深的链——最短路径会被旁路掩盖);**依赖环 = 硬错误**(环让"最深链"无定义,与根治依赖地狱同向)
- 超限 → 硬错误;出口 = 替换库 / exclude / 调大 max-depth;**无豁免机制**

### 2.4 版本规则与排序(语义感知 MVS)

- central 物种:任意 Maven 版本字符串;排序采用 Maven `ComparableVersion` 语义
- mj 原生物种:tag 去 `v` 前缀后必须 semver(`X.Y.Z`,可带 `-prerelease`);`mj publish` 时校验
- **MVS 收敛规则:**
  - 同 id 同 major → 取最大版本,与声明顺序无关
  - 同 id 跨 major(mj 原生物种)→ **硬冲突报错**,错误信息给出完整引入路径;出口 = 统一版本或 `[overrides]`(§2.1)。理由:跨 major 静默取大会让低 major 使用方运行期炸 `NoSuchMethodError`——正是 mj 要根治的沉默地狱
  - central 物种 best-effort:版本形如 semver 时按 major 规则;非 semver 形态(如 `2023.01`、`30.0-jre`)退回全局 max-wins
- version ranges(`[1.0,2.0)`)在 pom 解码与 mj.toml 中均报错(拒绝式解析)
- **retract 跳过**:描述符 `[retract]` 声明的版本(§3.4)不参与"取最大";消费方显式钉定被撤回版本 → 允许但输出告警(显式选择优先于作者撤回)

---

## 3. 描述符 `{artifact}-{version}.mj.toml`

仅用于 **mj 原生物种**(Maven-layout 仓库的元数据是 pom,不用描述符)。
发布产物三件套:`{name}-{version}.jar` + `{name}-{version}.mj.toml` + `{name}-{version}.sha256`。

### 3.1 字段(closed schema)

```toml
[project]
name = "mylib"            # 必填;必须与文件名 artifact 段一致,否则报错
version = "1.2.0"         # 必填;必须与文件名 version 段一致,否则报错

[java]
version = 21              # 可选;语义同 §2.1 [java]

[deps]                    # 可选;物种仅限 central / gh: / gitee: / URL
"com.example:foo" = "1.2.3"
```

### 3.2 禁止项(出现即报错)

`[repos]`、`[deps.{repo}]`、`[limits]`、`[scripts]`、`[overrides]`、任何未知节。

理由:仓库是消费方的配置;预算规则只有一份(消费方);scripts 只属于应用。**发布物里只有事实,规则只有一份。**

### 3.3 语义

描述符 `[deps]` 参与消费方闭包与深度预算,与消费方直接依赖同等对待。

### 3.4 [retract](可选节,吸收自 Go modules)

```toml
[retract]
"1.2.0" = "引入 #121 回归,请用 1.2.1"
```

- key = 被撤回的 semver 版本,value = 理由字符串(必填,告警信息会引用它)
- 解析器在"取最大"时跳过被撤回版本(§2.4);消费方显式钉定被撤回版本 → 允许但告警
- 撤回是事后声明:`mj publish` 发布某版本时,该版本自身不得出现在 `[retract]` 中;发布新版本可顺带撤回旧版本

---

## 4. mj.lock

### 4.1 结构(closed schema)

```toml
# 由 mj 生成,提交进版本库,勿手改
[[artifacts]]
id = "com.example:foo"    # central: "group:artifact";mj 原生: repo 名
version = "1.2.3"
source = "central"        # "central" / "gh:owner/repo" / "gitee:owner/repo" / "url"
                          # / 命名仓库的 base 原文(如 "local:vendor")
url = "https://…"         # 实际下载 URL(local: 仓库为 file:// URI),必须 https 或 file://
sha256 = "…"              # artifact 字节(jar),64 位十六进制,必填
meta-sha256 = "…"         # 元数据字节(pom.xml 或描述符);URL 叶子无元数据,省略该字段
depth = 1                 # 实际深度,>= 1
```

### 4.2 不变式

1. id 在闭包内唯一(MVS 单版本)
2. sha256 与 meta-sha256 必为 64 位十六进制(URL 叶子无元数据,meta-sha256 缺省);**每次下载都强制核对,任一不匹配即失败**(release asset 可变风险由它们兜底;元数据变了依赖图就变了——go.sum 同时哈希 zip 与 go.mod 的先例)。`local:` 仓库与远程同等钉定
3. url 必须 https
4. depth ≥ 1
5. 按 id 字典序输出(稳定 diff)
6. 一致性:lock 的依赖集合与 mj.toml + 描述符闭包一致;不一致 → 拒绝 build/run,提示 `mj lock`
7. 命中策略:lock 有效 → 直接采用(sha256 权威,不重新解析);`mj lock --refresh` 强制重解

---

## 5. 错误信息规范

- 文件解析错误:`{文件}:{行}:{列}: {描述}(期望 …,实际 …)`
- 依赖图错误:完整引入路径 + 当前预算值 + 出口建议(§2.3)
- 所有报错附带"下一步该敲什么命令"

---

## 6. 本 spec 未决项

- Gitee URL 展开的实测确认(Q8,M4 前)
- `[repos]` 回退链语法与 `mj.work.toml` 本地覆盖(V2 workspaces 设计时补入)
- `mj jdk` toolchain 管理(V2,方向已定:Temurin → `~/.mj/jdks`)
