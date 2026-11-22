# mjm 文件格式规范(SPEC)

状态:草案 v0.1(2026-11-21,待评审)
配套:[DESIGN.md](DESIGN.md)(决策与理由)。本文只定义**文件的语法与语义**,是实现验收的依据。

总原则:**拒绝式解析**。任何未在本 spec 中定义的节、键、值形态、语法糖 → 立即报错。
schema 封闭,拼写错误(`[dep]` ≠ `[deps]`)不会静默失效。

---

## 0. 三个文件

| 文件 | 谁写 | 谁读 | 提交版本库 |
|---|---|---|---|
| `mjm.toml` | 人 | mjm | 是 |
| `{artifact}-{version}.mjm.toml`(描述符) | `mjm publish` 生成 | mjm(消费方解析时) | 随发布物 |
| `mjm.lock` | mjm | mjm | 是 |

---

## 1. TOML 子集文法

> 唯一实现:freeway-commons `com.jujin.freeway.commons.toml`(DESIGN D14)——mjm.toml、描述符、mjm.lock 与 freeway 的 `application.toml` 共用一份解析器。

```
document      = *( ws / comment / table / array-table / keyval )
comment       = "#" *( 任意非换行字符 )
table         = "[" dotted-key "]"                  ; [deps] [deps.corp] [limits] …
array-table   = "[[" dotted-key "]]"                ; 仅 mjm.lock 使用
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

## 1.5 全局旗标

### `--offline`

**禁止本次运行的一切网络访问。** store 里已有的内容照常使用（会话缓存 → 持久索引 → 字节校验）；缺任何东西即失败，错误消息报出缺失的 URL，并说明如何填充。

不是"优先本地"：没有回退到网络的降级路径。让旗标有时说谎比没有旗标更糟。

每个 store 访问都经过同一个判定点（`ArtifactStore.ensure`），所以 `lock` / `build` / `test` / `info --source` 都自动遵守。

**不适用的命令**：`update`、`publish`、`outdated` —— 它们的存在就是联网操作。传入 `--offline` 报用法错误（退出码 2），而不是假装接受。

**`add` 的 discover 形态**：不带显式版本时（列举版本 = 读 `maven-metadata.xml`，或裸名走 Central 索引搜索）是联网操作，`--offline` 下报用法错误（退出码 2）——要离线就带上显式版本：`mjm add "<id>" <version>` 只读 store，`--offline` 下照常可用。

**为什么可行**：store 有持久化的 URL → digest 索引（`store/index/<sha256(url)>.url`）。没有它，索引未命中会是常态（每个 pom 都被当作"没见过"），`--offline` 必然失败。

---

## 2. mjm.toml

### 2.1 节与字段(closed schema)

**[project]**

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `name` | 字符串 | 是 | `^[a-z][a-z0-9-]*$`(用作 mjm 原生发布的 artifact 名) |
| `group` | 字符串 | 否 | 反向域名 id(`com.jujin8.mjm`)。发布到 http(s) 主机的 **Maven 布局必需**(路径按 group 展开),并写入描述符供消费方以 `group:artifact` 引用 |
| `version` | 字符串 | 是 | mjm 原生发布时必须 semver(§2.4) |
| `main` | 字符串 | 否 | 主类全名。`mjm run` 缺参时的入口;`mjm package` 写进 MANIFEST 的 `Main-Class`;`mjm bundle` 写进 launcher |
| `summary` | 字符串 | 否 | 一句话说明。写入发布描述符,由 `mjm info` 展示 |
| `homepage` | 字符串 | 否 | 项目主页,**必须 `https://`**。写入描述符 |
| `license` | 字符串 | 否 | SPDX 标识符(`Apache-2.0`、`MIT`)。写入描述符 |

**[java]**

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `version` | 整数 | 否 | 目标 major 版本。编译:`javac --release N`(主码与测试码同目标);要求当前 JDK major **>= N**,否则构建失败。缺省 = 不设限 |

> 注:mjm 自身基于 Freeway(JDK 25+),运行 mjm 需要一个 ≥25 的 JDK;用户项目可通过 `--release` 目标更低版本。

**[deps] 与 [deps.{repo}]**

key = 坐标,value 形态决定物种:

| 值形态 | 物种 | 要求 |
|---|---|---|
| `"1.2.3"` | central | 版本字符串非空、无空白;**禁止 version ranges**(`[1.0,2.0)`)。带 classifier 的依赖沿 pom 传递时,classifier 是身份的一部分(`group:artifact:classifier`),URL 为 `artifact-version-classifier.jar` |
| `"corp:1.2.3"` | 命名仓库 | 只查 `corp` 这个仓库,**不回退**;`corp` 必须在 `[repos]` 里声明(否则报错并列出可用仓库名);版本规则与 central 相同(非空、无空白、禁 ranges/snapshot) |
| `"gh:owner/repo@tag"` / `"gitee:owner/repo@tag"` | mjm 原生 | tag 为可选 `v` 前缀 + semver;展开规则见 §2.2 |
| `"https://…/x.jar"` | 裸 URL(叶子) | 必须 https、以 `.jar` 结尾 |

**歧义规则**(无例外):

1. `gh:` / `gitee:` / `https://` 前缀**先**匹配——它们是保留前缀,代表 mjm 自己的物种。
2. 其余值,若冒号前是**已声明**的仓库名(且冒号后非空),按「命名仓库」解析;声明了却没写对名字、
   或名字未声明 → 报错并列出可用仓库名(不默默当成 central 版本)。不匹配仓库名形式的(例如
   `1.2.3`)仍是 central 版本。

`gh` 与 `gitee` 是保留的仓库名:`[repos] gh = …` 直接报错(它会和物种前缀撞车),换一个名字。
`local` / `http` / `https` / `file` 不保留。

- `[deps.{repo}]` 分区写法为 **legacy**:仍可读、语义与值形态完全相同,但 `mjm add` 不再产生它
- 同一坐标同时出现在 `[deps]`、任何 `[deps.{repo}]`,或两种写法混用 → 报错(身份撞车)
- `{repo}` 未在 `[repos]` 中声明 → 报错

**[dev-deps] 与 [dev-deps.{repo}]** — 仅测试可见的依赖(Cargo `dev-dependencies`、uv `[dependency-groups]` 先例)

独立顶层节,不是 `[deps.dev]`——后者已是"来自仓库 dev 的依赖"。声明规则与 `[deps]` **完全一致**
(同样的物种形态、同样的 `{repo}` 分组、同样的精确版本约束),差别只在可见性:

| 面 | `[deps]` | `[dev-deps]` |
|---|---|---|
| 编译可见性(strict visibility) | ✅ | ❌ 从不出现在 javac 参数 |
| `mjm run` 运行时 classpath | ✅ | ❌ |
| `mjm package` / `mjm bundle` 产物 | ✅ | ❌ |
| 发布描述符(传播给消费者) | ✅ | ❌ **绝不外泄** |
| `mjm test` 编译与运行 classpath | ✅ | ✅ 这就是它存在的理由 |
| mjm.lock | ✅ 行无 scope | ✅ 行带 `scope = "dev"` |
| 深度预算 / 跨 major 冲突检测 | ✅ | ✅ 同等对待 |

- 同一个 id 同时出现在 `[deps]` 与 `[dev-deps]` → 报错(一个声明一个作用域)
- 传递性作用域:仅从 `[dev-deps]` 可达的工件一律 `dev`;既能从 `[deps]` 也能从 `[dev-deps]` 可达的
  工件为 `main`(**生产用途胜过测试用途**——同 Cargo 规则)
- 读兼容:mjm.lock 中缺省 `scope` 即 `main`,所有 `[dev-deps]` 之前的锁文件原样可读

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

- key = 依赖 id(引号字符串:`group:artifact` 或 mjm 原生 repo 名),value = 版本字符串
- central 物种:任意 Maven 版本字符串;mjm 原生物种:必须 semver;URL 物种无版本概念 → 其 id 出现在此 → 报错
- 语义:MVS 之上的显式钉定——收敛时该 id 直接取 override 版本,忽略其他来源的版本约束;跨 major 硬冲突在 override 应用**之后**检查(它是合法出口)
- 解析后闭包中不存在该 id → 告警(提示清理残留声明)
- 被 override 的 id 必须在 `mjm tree` / `mjm why` 输出中标注
- value 为 version ranges → 报错(与 deps 一致)

**[scripts]**

- key = 任务名,value = 命令字符串
- 执行方式:经系统 shell(`sh -c`),工作目录 = 项目根,与 npm scripts 一致
- `mjm run <script-name>` 优先于 `<main-class>`:先查 [scripts],命中则执行脚本;未命中才走 java 主类路径
- **注入环境变量**(脚本据此启动项目,无需自己拼 classpath):

  | 变量 | 值 |
  |---|---|
  | `MJM_CLASSPATH` | `target/classes` + 闭包中所有 MAIN jar,path-separator 连接。缺 build 产物或 mjm.lock 时不设置(脚本仍运行) |
  | `MJM_HOME` | 项目根目录的绝对路径 |

  例:`launch = "java -cp \"$MJM_CLASSPATH\" com.example.Main"`
- **额外参数**以位置参数传入:`mjm run task a b` → `sh -c "<command>" task a b`,命令里用 `"$1"` `"$2"` 取值(第一个操作数是 shell 的 `$0`,被脚本名占位)
- 脚本退出码即 `mjm run` 的退出码

**[build]**

| 键 | 类型 | 规则 |
|---|---|---|
| `parameters` | 字符串数组 | 追加到 javac 的旗标,如 `-parameters`(freeway 系 BeanIntrospector 依赖参数名) |
| `processors` | 字符串数组 | 注解处理器坐标 `group:artifact:version`(版本必填——处理器是构建工具,不是由消费者选版本的库)。解析进同一张锁图,`scope = "processor"`,只进 `javac -processorpath`,不进编译 classpath、运行闭包或发布描述符 |

缺省 = 空数组;未知键 → 报错

**[test]**

| 键 | 类型 | 规则 |
|---|---|---|
| `include` | 字符串数组 | 只编译/运行匹配的测试源;`*` 匹配任意字符;空 = 全部 |
| `exclude` | 字符串数组 | 跳过匹配的测试源;排除优先于包含 |

匹配对象是文件名或相对路径(surefire 的 `*Slow*` 写法可用)。

未知节、未知键 → 报错。

### 2.2 值形态展开(mjm 原生)

```
gh:{owner}/{repo}@{tag}
  → https://github.com/{owner}/{repo}/releases/download/{tag}/{repo}-{version}.jar
  → 及同名 .mjm.toml 与 .sha256

gitee:{owner}/{repo}@{tag}
  → https://gitee.com/{owner}/{repo}/releases/download/{tag}/{repo}-{version}.jar
```

`{version}` = tag 去 `v` 前缀。URL 模式待 Q8 实测确认(Gitee 侧)。

### 2.3 深度预算(语义,细节见 DESIGN.md §4.2)

- compile/runtime 闭包计入预算；`test-scope` 指 **Maven pom 的 test/provided/system scope**(解码时即丢弃,不进图)
  mjm 自己的 `[dev-deps]` **同等计入预算**(它就是 `[deps]` 的另一个可见性分组,不是 scope 豁免)——见 §2.1 对照表
- MVS 收敛后的最终图上计数;**深度 = 从声明根出发的最长引入路径**(预算的真意是暴露最深的链——最短路径会被旁路掩盖);**依赖环 = 硬错误**(环让"最深链"无定义,与根治依赖地狱同向)
- 超限 → 硬错误;出口 = 替换库 / exclude / 调大 max-depth;**无豁免机制**

### 2.4 版本规则与排序(语义感知 MVS)

- central 物种:任意 Maven 版本字符串;排序采用 Maven `ComparableVersion` 语义
- mjm 原生物种:tag 去 `v` 前缀后必须 semver(`X.Y.Z`,可带 `-prerelease`);`mjm publish` 时校验
- **MVS 收敛规则:**
  - 同 id 同 major → 取最大版本,与声明顺序无关
  - 同 id 跨 major(mjm 原生物种)→ **硬冲突报错**,错误信息给出完整引入路径;出口 = 统一版本或 `[overrides]`(§2.1)。理由:跨 major 静默取大会让低 major 使用方运行期炸 `NoSuchMethodError`——正是 mjm 要根治的沉默地狱
  - central 物种 best-effort:版本形如 semver 时按 major 规则;非 semver 形态(如 `2023.01`、`30.0-jre`)退回全局 max-wins
- version ranges(`[1.0,2.0)`)在 pom 解码与 mjm.toml 中均报错(拒绝式解析)
- **retract 跳过**:描述符 `[retract]` 声明的版本(§3.4)不参与"取最大";消费方显式钉定被撤回版本 → 允许但输出告警(显式选择优先于作者撤回)

---

## 3. 描述符 `{artifact}-{version}.mjm.toml`

仅用于 **mjm 原生物种**(Maven-layout 仓库的元数据是 pom,不用描述符)。
发布产物三件套:`{name}-{version}.jar` + `{name}-{version}.mjm.toml` + `{name}-{version}.sha256`。

### 3.1 字段(closed schema)

```toml
[project]
name = "mylib"            # 必填;必须与文件名 artifact 段一致,否则报错
group = "com.example"     # 可选;发布者声明的 group,消费方据此以 group:artifact 引用
version = "1.2.0"         # 必填;必须与文件名 version 段一致,否则报错
summary = "..."           # 可选;一句话说明这个库是做什么的(供 mjm info 展示)
homepage = "https://..."  # 可选;必须 https;项目主页
license = "Apache-2.0"    # 可选;SPDX 标识符

[java]
version = 21              # 可选;语义同 §2.1 [java]

[deps]                    # 可选;物种仅限 central / gh: / gitee: / URL
"com.example:foo" = "1.2.3"
```

### 3.2 禁止项(出现即报错)

`[repos]`、`[deps.{repo}]`、`[dev-deps]`、`[limits]`、`[scripts]`、`[overrides]`、任何未知节。

理由:仓库是消费方的配置;预算规则只有一份(消费方);scripts 只属于应用。**发布物里只有事实,规则只有一份。**

### 3.3 语义

描述符 `[deps]` 参与消费方闭包与深度预算,与消费方直接依赖同等对待。

`[project]` 的 `summary` / `homepage` / `license` 是**标识事实**:发布后不可变、不参与解析、
仅由 `mjm info` 读取展示。它们与 `[deps]` 同属"发布物里的事实",不是规则。

### 3.4 [retract](可选节,吸收自 Go modules)

```toml
[retract]
"1.2.0" = "引入 #121 回归,请用 1.2.1"
```

- key = 被撤回的 semver 版本,value = 理由字符串(必填,告警信息会引用它)
- 解析器在"取最大"时跳过被撤回版本(§2.4);消费方显式钉定被撤回版本 → 允许但告警
- 撤回是事后声明:`mjm publish` 发布某版本时,该版本自身不得出现在 `[retract]` 中;发布新版本可顺带撤回旧版本

---

## 4. mjm.lock

### 4.1 结构(closed schema)

```toml
# 由 mjm 生成,提交进版本库,勿手改
[[artifacts]]
id = "com.example:foo"    # central: "group:artifact";mjm 原生: repo 名
version = "1.2.3"
source = "central"        # "central" / "gh:owner/repo" / "gitee:owner/repo" / "url"
                          # / 命名仓库的 base 原文(如 "local:vendor")
url = "https://…"         # 实际下载 URL。local: 仓库写为相对项目根的 file: 形式
                          # (如 file:vendor/com/…/x.jar),读取时按项目根展开;
                          # 路径落在项目根之外(如 local:/opt/repo)仍写绝对 file:///…;
                          # 旧的绝对 file:/// 锁行照旧可读
sha256 = "…"              # artifact 字节(jar),64 位十六进制,必填
meta-sha256 = "…"         # 元数据字节(pom.xml 或描述符);URL 叶子无元数据,省略该字段
depth = 1                 # 实际深度,>= 1
scope = "dev"             # 可选:[dev-deps] 可达的工件。缺省 = main
                          #       另一取值 "processor"(§2.1 [build].processors)
```

### 4.2 不变式

1. id 在闭包内唯一(MVS 单版本)
2. sha256 与 meta-sha256 必为 64 位十六进制(URL 叶子无元数据,meta-sha256 缺省);**每次下载都强制核对,任一不匹配即失败**(release asset 可变风险由它们兜底;元数据变了依赖图就变了——go.sum 同时哈希 zip 与 go.mod 的先例)。`local:` 仓库与远程同等钉定
3. url 必须 https
4. depth ≥ 1
5. 按 id 字典序输出(稳定 diff)
6. 一致性:lock 的依赖集合与 mjm.toml + 描述符闭包一致;不一致 → 拒绝 build/run,提示 `mjm lock`
7. 命中策略:lock 有效 → 直接采用(sha256 权威,不重新解析);`mjm lock --refresh` 强制重解

---

## 5. 错误信息规范

- 文件解析错误:`{文件}:{行}:{列}: {描述}(期望 …,实际 …)`
- 依赖图错误:完整引入路径 + 当前预算值 + 出口建议(§2.3)
- 所有报错附带"下一步该敲什么命令"

---

## 6. 本 spec 未决项

- Gitee URL 展开的实测确认(Q8,M4 前)
- `[repos]` 回退链语法与 `mjm.work.toml` 本地覆盖(V2 workspaces 设计时补入)
- `mjm jdk` toolchain 管理(V2,方向已定:Temurin → `~/.mjm/jdks`)
