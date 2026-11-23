# mjm 规范（SPEC）：文件与 CLI 契约

状态:现行;最后核对 2026-11-23

**SPEC 是规范;DESIGN 是理由;冲突以 SPEC 为准。** 本文定义文件格式、CLI 契约、发行物布局与
Maven 解码范围,是实现与验收的依据;取舍与替代方案见 [DESIGN.md](DESIGN.md)。

总原则:**拒绝式解析**。任何未在本 spec 中定义的节、键、值形态、语法糖 → 立即报错。
schema 封闭,拼写错误(`[dep]` ≠ `[deps]`)不会静默失效。

---

## 0. 四个文件

| 文件 | 谁写 | 谁读 | 提交版本库 |
|---|---|---|---|
| `mjm.toml` | 人 | mjm | 是 |
| `{artifact}-{version}.mjm.toml`(描述符) | `mjm publish` 生成 | mjm(消费方解析时) | 随发布物 |
| `mjm.lock` | mjm | mjm | 是 |
| `mjm.ws.toml` | `mjm init --ws` 或人 | mjm | **否**(本地文件,§5) |

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

## 2. mjm.toml

### 2.1 节与字段(closed schema)

**[project]**

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `name` | 字符串 | 是 | `^[a-z][a-z0-9-]*$`(用作 mjm 原生发布的 artifact 名) |
| `group` | 字符串 | 否 | 反向域名 id(`com.jujin8.mjm`)。写入描述符时它只是**标识事实**(`mjm info` 展示);mjm 只发布 mjm 布局,**不写 Maven 布局**,所以它不参与任何发布路径。消费 Maven 仓库时坐标仍是 `group:artifact`(读取侧,不变)。原生工件一律用工件名寻址,不存在 `group:artifact` 的形式 |
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
| `"corp:1.2.3"` | 命名仓库 | 只查 `corp` 这个仓库,**不回退**;`corp` 必须在 `[repos]` 里声明(否则报错并列出可用仓库名);版本规则与 central 相同(非空、无空白、禁 ranges/snapshot)。若 `corp` 是 Maven 布局 → 读 pom;若是 `mjm:` 仓库 → 读描述符(见下) |
| `"gh:owner/repo@tag"` | mjm 原生 | tag 为可选 `v` 前缀 + semver;展开规则见 §2.2 |
| `"https://…/x.jar"` | 裸 URL(叶子) | 必须 https、以 `.jar` 结尾 |

**`mjm:` 仓库**(`[repos] mine = "mjm:https://repo.example/"` 或 `"mjm:local:../vendor"`):mjm 自己的两层布局,发布物三件套
平铺在 `<base>/<name>/<version>/` 下(`<name>` = 发布方 `[project].name`),**不需要 `[project].group`**。
传输可以是 HTTPS(`mjm:https://…`)或本地目录(`mjm:local:<dir>`,`~` 展开、相对路径相对项目根,
`mjm publish mjm:local:<dir>` 直接写入)。
依赖写成 `"<name>" = "mine:<semver>"`(或 legacy `[deps.mine] "<name>" = "<semver>"`);元数据取自同目录的
`.mjm.toml` 描述符,`[project].name` 必须与 key 一致(由 `DescriptorReader` 校验)。描述符里的裸名+裸版本
指向同一仓库的兄弟工件(描述符只写事实,不写仓库规则)——**这条兄弟规则仅适用于从 `mjm:` 仓库取到的
描述符**;从 `gh:` 取到的描述符里写裸名不会变成同仓库,仍按 Central 坐标报错。`mjm:https://` 与
`mjm:local:` 都支持(loopback `http://` 仅供测试);store 索引与 `--offline` 行为不变。

**歧义规则**(无例外):

1. `gh:` / `https://` 前缀**先**匹配——它们是保留前缀,代表 mjm 自己的物种。
2. 其余值,若冒号前是**已声明**的仓库名(且冒号后非空),按「命名仓库」解析;声明了却没写对名字、
   或名字未声明 → 报错并列出可用仓库名(不默默当成 central 版本)。不匹配仓库名形式的(例如
   `1.2.3`)仍是 central 版本。

`gh` 与 `gitee` 是保留的仓库名:`[repos] gh = …` / `[repos] gitee = …` 直接报错。
`gh:` 是物种前缀;`gitee:` 虽已移除,但它的值必须继续产出迁移错误,不能被一个同名仓库静默接管。
`local` / `http` / `https` / `file` 不保留。

> **`gitee:` 已移除(2026-11-22)**:Gitee 的 release 附件即使匿名下载也要令牌,违反“零配置”原则。
> 替代写法:直接写 release jar 的 `https://…jar` 叶子,或发布到自己的主机并在 `[repos]` 声明后用
> 值形态引用。遇到 `gitee:` 会报错并给出这两种写法。

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

每个仓库是一张子表,**类型决定布局**:

```toml
[repos.corp]          # Maven 布局(缺省 type 可省)
url = "https://nexus.corp/"

[repos.mine]          # 同一台静态主机,也可以是 mjm 布局
type = "mjm"
url = "https://my.host/"

[repos.vendor]        # 裸路径 = 本地目录(~ 与相对项目根均可)
type = "mjm"
url = "vendor"
```

| 字段 | 规则 |
|---|---|
| key | 仓库名,`^[a-z][a-z0-9-]*$` |
| `type` | `maven` \| `mjm`(封闭枚举;缺省 `maven`) |
| `url` | `https://…`(loopback `http://` 仅测试)或文件系统路径。以 http 开头即 URL,否则即目录 |

| 布局(由 `type` 决定) \ 位置(由 `url` 决定) | HTTPS | 本地路径 |
|---|---|---|
| **`type = "maven"`** | `https://host/…` | `url = "vendor"` |
| **`type = "mjm"`** | `url = "https://host/"` | `url = "vendor"` |

布局是声明的属性,不是 URL 的属性:同一台 https 主机可以装任一布局,所以协议必须由 `type`
声明,不能从 URL 前缀猜。子表里只允许 `type`、`url` 两个键,未知键报错(带 `file:line:column`)。

**legacy 字符串写法**(仍可读,等价于上表):
`"https://…"` = maven+https;`"local:<dir>"` = maven+本地;`"mjm:https://…"` = mjm+https;
`"mjm:local:<dir>"` = mjm+本地。写锁时两种写法都归一成这四个规范串,锁格式不变。

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
```

`{version}` = tag 去 `v` 前缀。

> `gitee:` 曾在 2026-11-21 按同样的形状实装,2026-11-22 移除:附件匿名下载返回 403(需 Bearer
> 令牌),与“声明即用”相悖。需要 Gitee 上的构件时,写裸 URL 叶子或在自建主机上用 `[repos]`。

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
group = "com.example"     # 可选;标识事实,由 mjm info 展示;原生工件用工件名寻址,无 group:artifact
version = "1.2.0"         # 必填;必须与文件名 version 段一致,否则报错
summary = "..."           # 可选;一句话说明这个库是做什么的(供 mjm info 展示)
homepage = "https://..."  # 可选;必须 https;项目主页
license = "Apache-2.0"    # 可选;SPDX 标识符

[java]
version = 21              # 可选;语义同 §2.1 [java]

[deps]                    # 可选;物种仅限 central / gh: / URL
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
source = "central"        # "central" / "gh:owner/repo" / "url"
                          # / 命名仓库的声明原文(如 "local:vendor"、"mjm:https://repo.example/"、"mjm:local:../vendor")
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
3. url 必须 https;唯一例外是 loopback `mjm:` 仓库(`http://localhost` / `http://127.0.0.1`,仅供测试)
4. depth ≥ 1
5. 按 id 字典序输出(稳定 diff)
6. 一致性:lock 的依赖集合与 mjm.toml + 描述符闭包一致;不一致 → 拒绝 build/run,提示 `mjm lock`。`tree`/`why` 也以锁为权威:二者先读锁、再解析 manifest 只为补父子边,id/version/source/depth/scope 任一不一致即 exit 1(至多 10 行差异,不输出图);无锁 → exit 1
7. 命中策略:lock 有效 → 直接采用(sha256 权威,不重新解析);`mjm lock` **总是重解**并重写锁,
   `--check` 只比较
8. 兼容性:旧锁里 `source` 以 `gitee:` 开头的行,**读锁即报**可执行的迁移错误(gitee: 物种已移除),而不是留给下游命令各自报 URI 错误;`source` 以 `mjm:` 开头的行照常读

---

## 5. `mjm.ws.toml`(本地工作区文件)

列出同一目录树里的若干模块,让彼此依赖时用本地的 `target/classes` 取代已发布的 jar。
**只影响字节来源,不影响解析与锁**(设计记录见 `docs/workspace-design.md`)。

```toml
[ws]
modules = ["libs/core", "apps/web"]
```

### 5.1 字段(closed schema)

| 节 | 键 | 类型 | 规则 |
|---|---|---|---|
| `[ws]` | `modules` | 字符串数组,必填 | 相对本文件所在目录;每一项必须是一个**存在且含 `mjm.toml`** 的目录 |

- 没有其他节、没有其他键。任何未列出的节/键 → 报错(`拒绝式解析`)。
- 路径接受绝对路径,但生成工具总写相对路径(正斜杠、字典序)。
- `modules = []` → 解析错误:本 TOML 子集不支持空字符串数组(§1)。
  (`mjm init --ws` 扫不到模块时也会拒绝写文件——它不生成一个空工作区。)

### 5.2 语义

- **查找**:从当前目录向上找最近的 `mjm.ws.toml`;没有则按单模块工作(与本文档其余部分完全一致)。
- **根不能是模块**:同一目录同时含 `mjm.toml` 与 `mjm.ws.toml` → 报错。
- **重复**:两个条目指向同一 realpath → 报错;两个模块产出同一个 id → 报错。
- **拓扑序**:模块间的依赖关系推出执行顺序;成环 → 报错并给出环。
- **不提交**:这是本机文件(git 由用户管,mjm 不写 `.gitignore`、不检查跟踪状态)。
- **不参与发布**:发布物只描述已发布版本;本地 classes 的替换不写进锁、不写进描述符。

### 5.3 与锁的关系

`mjm.lock` 的格式与语义**完全不变**:每个模块仍各有一份锁,锁里仍是从仓库解析出来的
工件(sha256 权威)。替换发生在装配 classpath 时,因此锁在任何机器上都一致。

---


## 6. CLI 契约

### 6.1 命令

20 个命令,以本表为准(`mjm --help` 只打印同一份清单的摘要)。

| 命令 | 契约 |
|---|---|
| `mjm init [--pom] [--ws] [--force] [paths...]` | 写 `mjm.toml` 骨架;`--pom` 从 `pom.xml` 迁移并逐条报告不可表达的事实——本地 parent 从 `relativePath` 读取,本地不存在时经 store 从 Central 读取远端 parent(`--offline` 只用已缓存内容);BOM import/快照/私有仓库不自动解析;`--ws` 写 `mjm.ws.toml`(§5),此时 `paths...` 可显式指定模块目录。**工作区根目录拒绝 `init`/`--pom`/`--force`**(exit 1),`--ws` 仍可用 |
| `mjm add <key> [value]` | key: `group:artifact`(Central)、裸名(搜索)、`owner/repo@tag`(原生简写)。value: `1.2.3`、`<repo>:1.2.3`、`gh:owner/repo@tag`、裸 `https://...jar`。无 value 时:`group:artifact`/裸名只查询,不写 `mjm.toml`(查询成功 exit 0);`owner/repo@tag` 会写入 `[deps]` 并重锁 |
| `mjm remove <id>` | 删除一行声明并重锁 |
| `mjm lock [--check]` | 解析并写出 `mjm.lock`;**总是重解**。`--check` 只在内存生成并与磁盘逐字节比较,不写文件。未知参数是用法错误(exit 2) |
| `mjm build` | javac 编译 `src/main/java`,严格可见性 classpath(只含 MAIN 声明);`--release` 取 `[java].version`;processor 走 `-processorpath`。至少一个 Java 源或 `src/main/resources` 下的文件必须存在:有源则编译并拷资源;无源但有资源则只创建 `target/classes` 并拷资源、exit 0;两者都没有则报错 |
| `mjm test` | fork JVM:`@MjmTest` + JUnit Platform;classpath 含 MAIN + dev,processor 只走 javac 的 `-processorpath`;每次运行先清空 `target/test-results/`(空测试/编译失败也会清空),XML 报告写该目录 |
| `mjm run [<main>] [args…]` | 在 runtime classpath(排除 dev)上运行;无参数时用 `[project].main`;`[scripts]` 优先于同名主类。target 在 `--` 之前(可省略);mjm 旗标只在 target 之前生效;`--` 之后**全部**是程序参数(§6.2) |
| `mjm tree [--json]` | 依赖树 + 深度(processor 根也会列出并标 `, processor`);**以 `mjm.lock` 为准**:先读锁,再解析 manifest 只为补父子边;id/version/source/depth/scope 任一不一致 → exit 1、至多 10 行差异、不输出图 |
| `mjm why <id>` | 每个引入路径与深度;同样以锁为准,漂移拒绝画图 |
| `mjm info <id> [--source]` | 身份/来源/依赖/源码地址;`--source` 取源码树。标题按 scope 标记:`[dev]`、`[processor]`,MAIN 无标记 |
| `mjm classpath [--test] [--file <path>]` | IDE 用 classpath;默认编译可见性;`--test` 为测试闭包(MAIN + dev,**排除 processor**——与 `mjm test` 的差异见 REMINDS §14);路径指向 store 内容寻址 jar,不拷贝 |
| `mjm package [--uber] [--main <class>]` | `target/dist/<name>-<version>.jar`;`--uber` 合并闭包输出 `...-uber.jar`;`--main` 只影响本 jar 的 MANIFEST,不影响 `bundle`/`run` 的 `[project].main` |
| `mjm bundle [--toolchain]` | `target/dist/<name>-<version>/`(bin/lib/setup.sh/uninstall.sh/SHA256SUMS)+ `.tar.gz`。需要 `[project].main`(缺失即报错);setup.sh 的 JDK 要求取 `[java].version`,未声明则取构建 JDK major;安装前用 `sha256sum -c`/`shasum -a 256 -c` 校验 SHA256SUMS,lib/bin 经 staging 交换并保留 store/config;默认不带 test toolchain,`--toolchain` 才写入 `lib/toolchain` |
| `mjm clean [--target (default) \| --dist]` | 默认整棵删 `target/`;`--dist` 只删 `target/dist/` 保留 classes;永不触碰 store |
| `mjm cache dir \| size \| prune \| purge [--dry-run] [--yes]` | `dir`/`size` 只读;`prune` 删可证明无用之物,无需确认;`purge` 删 store 与 sources,是**破坏性**命令——无 `--yes` 时打印路径/大小并 exit 2,`--dry-run` 只展示不删除(exit 0),`--yes` 才执行;`clean` 是兼容 alias,支持同样 flags |
| `mjm outdated [--json] [--dev]` | **只读 `mjm.lock`**(不需要 `mjm.toml`);已钉版本 vs 仓库可用版本;默认只查 MAIN,`--dev` 含 dev/processor;`gh:`/`url`/`local:`/`mjm:` 列为未检查;跨 major 单列;完成即 exit 0(有更新也是 0) |
| `mjm audit [--json] [--dev]` | OSV 漏洞查询;默认只查 MAIN,`--dev` 才含 dev/processor;只查 Central 坐标,其余列 `notChecked` 并给原因;漏洞或查询失败 exit 1 |
| `mjm publish [target]` | 写三件套;目标只接受 `gh:`、`mjm:https:`、`mjm:local:`(§6.6) |
| `mjm upgrade` / `mjm update`(alias) | 自升级(同一实现) |
| `mjm release [target] [--dry-run]` | 发布 `mjm bundle` 产生的安装包:`gh:<owner>/<repo>`、`mjm:https://host/`(loopback `http://` 仅供测试)、`mjm:local:<dir>`;无 target 只准备 `target/dist/release/` 并打印上传清单。**不隐式 build/test/bundle**;发布后回读并校验 tar 的 sha256(§7.3) |

### 6.2 参数约定

- **未知参数是用法错误**:exit 2,不静默忽略(`build`/`test`/`bundle`/`upgrade`(alias `update`) 无参数;`why` 恰好一个 id;`publish` 最多一个 target)。
- **`--` 结束 mjm 的选项**:之后的内容作为位置参数交给命令(`run` 交给程序)。
- **`--help` / `-h`**:只在 `--` 之前、且是唯一剩余参数时生效;`mjm --help` 打印清单 exit 0。
- **`run` 的旗标边界**:mjm 的 `--module`/`--offline` 只在 target 之前生效;target 之后(或 `--` 之后)全部是程序参数。

### 6.3 退出码

| 码 | 含义 |
|---|---|
| 0 | 成功;`outdated` 完成(有更新也是 0);`audit` 无漏洞;`tree`/`why` 一致 |
| 1 | 失败;`audit` 有漏洞;`lock --check`/`tree`/`why` 漂移;查询失败 |
| 2 | 用法错误 |

本表描述 mjm 自身的退出码。`run` 会把程序/脚本的退出码原样返回;`test` 会把 fork 出的测试 JVM 的
退出码原样返回。`why` 找不到 id 是 0(有效的否定答案);`info` 找不到 id 是 1。

### 6.4 工作区策略

| 策略 | 命令 | 根目录行为 |
|---|---|---|
| orchestrate | `lock`、`build`、`test`、`package`、`bundle`、`clean` | 按依赖拓扑序逐模块;`--module <path>` 只跑一个;`build`/`package`/`bundle`/`lock` 遇错即停,`test`/`clean` 继续并汇总 |
| requireModule | `add`、`remove`、`run`、`classpath`、`info`、`tree`、`why`、`publish`、`audit`、`outdated` | 无 `--module` → exit 2 标准句;带 `--module` → 在该模块执行 |
| global | `init`、`cache`、`upgrade`(alias `update`) | 不参与编排;`init` 在根目录拒绝(§6.1) |

### 6.5 输出与 JSON

`tree`、`outdated`、`audit` 支持 `--json`:stdout 只有**一个** JSON 文档,诊断/警告走 stderr。
字段集合如下(新增字段向后兼容;删改需新版本)。

**tree**

| 字段 | 类型 |
|---|---|
| `project` | `{name, version}` |
| `budget` | 整数(`max-depth`) |
| `roots[]` | 声明根 id:`[deps]` → `[dev-deps]` → processors,按各自声明顺序 |
| `artifacts[]` | `{id, version, depth, declared, dev, scope, local?, children[]}`;按 id 排序,`children` 排序。`scope` 为 `main`/`dev`/`processor`;workspace 本地替换时 `local` 为相对工作区根的路径(否则省略) |

**outdated**

| 字段 | 类型 |
|---|---|
| `upToDate` | 布尔 |
| `updates[]` | `{id, pinned, available, major}` |
| `notChecked[]` | `{id, reason}` |
| `failures[]` | `{id, error}` |

`notChecked[].reason` 与 `audit` 共用同一词表(见下)。

**audit**

| 字段 | 类型 |
|---|---|
| `vulnerabilities[]` | `{id, version, advisories[]}`,其中 advisory 为 `{id, severity?, summary?, fixed?, aliases[]}` |
| `notChecked[]` | `{id, reason}` |
| `failures[]` | `{id, error}` |

`notChecked[].reason`(`outdated` 与 `audit` 共用):

| 值 | 含义 |
|---|---|
| `native-release` | `gh:owner/repo` release(标签就是版本) |
| `bare-url` | 裸 `https://…jar` 叶子(无元数据) |
| `mjm-repository` | `mjm:` 布局仓库(含 `mjm:local:`;无版本索引/OSV 坐标) |
| `local-repository` | 本地 Maven 布局目录(无版本索引) |
| `named-repository` | 命名 HTTPS Maven 仓库;无法解析的 source 也归到这里 |

### 6.6 publish 与 release

`publish` 发布**库**的三件套(jar/描述符/sha256,§7.1),目标只接受 `gh:`、`mjm:https://`、`mjm:local:`。
`release` 发布**应用安装包**(`bundle` 的 tar.gz + bootstrap setup),目标是同一组三种;两者都不接受
Maven 布局。区别在于产物:库给依赖解析用,安装包给最终用户 `curl | bash`。

### 6.6 publish 目标

只接受 mjm 自己能回读的目标:`gh:owner/repo`、`mjm:https://host/`(loopback `http://` 仅供测试)、
`mjm:local:<dir>`;无 target 只准备 `target/dist/` 三件套。
**Maven 布局不是发布目标**:mjm 不写 POM,`https://host/`、`http://host/`、`local:<dir>` 一律拒绝(exit 1)。
Maven 布局仓库仍可**读取**(`[repos]` 的 `type = "maven"`,§2.1)。

### 6.7 全局旗标 `--offline`

**禁止本次运行的一切网络访问。** store 里已有的内容照常使用（会话缓存 → 持久索引 → 字节校验）；缺任何东西即失败，错误消息报出缺失的 URL，并说明如何填充。

不是"优先本地"：没有回退到网络的降级路径。让旗标有时说谎比没有旗标更糟。

每个 store 访问都经过同一个判定点（`ArtifactStore.ensure`），所以 `lock` / `build` / `test` / `info --source` 都自动遵守。

**不适用的命令**：`upgrade`(alias `update`)、`publish`、`outdated`、`audit` —— 它们的存在就是联网操作。传入 `--offline` 报用法错误（退出码 2），而不是假装接受。

**`add` 的 discover 形态**：不带显式版本时（列举版本 = 读 `maven-metadata.xml`，或裸名走 Central 索引搜索）是联网操作，`--offline` 下报用法错误（退出码 2）——要离线就带上显式版本：`mjm add "<id>" <version>` 只读 store，`--offline` 下照常可用。

**为什么可行**：store 有持久化的 URL → digest 索引（`store/index/<sha256(url)>.url`）。没有它，索引未命中会是常态（每个 pom 都被当作"没见过"），`--offline` 必然失败。

---

## 7. 发行物布局

### 7.1 发布三件套

```
<name>-<version>.jar
<name>-<version>.mjm.toml     # 描述符(§3)
<name>-<version>.sha256       # jar 字节的 sha256
```

生成在 `target/dist/`;`mjm package` 产出 jar,`mjm publish` 写描述符与 sha256。
发布**只发 mjm 布局**:`mjm:https://` 的 `<base>/<name>/<version>/` 或 `mjm:local:<dir>` 的同构目录;
Maven 布局(`group/...`,需要 POM)不是发布目标(§6.6)。

### 7.2 bundle 形态

```
target/dist/<name>-<version>/
  bin/<name>          # launcher(注释写明 <name> <version>)
  lib/<name>-<version>.jar
  lib/toolchain/      # 仅 mjm bundle --toolchain
  setup.sh            # JDK 检查 + SHA256SUMS 校验 + staging 交换
  uninstall.sh        # 同时装到 $HOME/.<name>/uninstall.sh;提示手动清 PATH 行
  SHA256SUMS
target/dist/<name>-<version>.tar.gz
```

---

## 8. 错误信息规范

- 文件解析错误:`{文件}:{行}:{列}: {描述}(期望 …,实际 …)`
- 依赖图错误:完整引入路径 + 当前预算值 + 出口建议(§2.3)
- 所有报错附带"下一步该敲什么命令"

---

## 9. 本 spec 未决项

**已记录的 known gap(行为修复各自独立成单)**:


**其他**:

- `[repos]` 回退链语法与私库排除(V2)
- ~~`mjm jdk` toolchain 管理~~ —— 2026-11-22 决定不做(见 DESIGN.md §12 Q5):只校验、不管理

---

## 附录 A Maven POM 解码范围

mjm 只把 pom 当作 Central 的**线路格式**读取,不追求 Maven 语义保真;范围如下(理由见 DESIGN §5)。

**支持**:

- parent POM 递归链(版本常藏在 parent)
- 属性插值(`${project.version}` 等)
- `dependencyManagement` 与 BOM `<scope>import</scope>`
- `dependencies` 中 scope = compile / runtime 的条目;`dependencyManagement` 提供的 scope 同样参与决议(依赖自身省略 scope 时生效)
- exclusions、classifiers(按需)、packaging: jar / pom / bundle(按 jar 处理)

**拒绝并报错**:

- version ranges、snapshots
- 非 jar/pom/bundle 的 packaging
- pom 中声明的 `<repositories>`:不跟随(仓库只能由 `mjm.toml` 的 `[repos]` 指定)

**范围**:只保证"把库拖下来且传递依赖正确";scopes 只关心 compile/runtime。

### 7.3 release 布局与 bootstrap

`mjm release` 的输入是 `target/dist/<name>-<version>.tar.gz`(已由 `mjm bundle` 产生),产物在
`target/dist/release/`:`setup.sh`(bootstrap)、`<tar>.sha256`、`release.json`。

`release.json` 至少:`name`、`version`、`artifact`、`sha256`、`jdk`、`mjm`、`platform`(当前 `any`)。

**目标布局**(三种目标同构):

```
gh:<owner>/<repo>      https://github.com/<owner>/<repo>/releases/download/<tag>/
                         <name>-<version>.tar.gz | setup.sh | <tar>.sha256 | release.json
                       稳定入口: .../releases/latest/download/setup.sh
mjm:https://host/      <base>/<name>/<version>/release/
                         <name>-<version>.tar.gz | setup.sh | <tar>.sha256 | release.json
                       <base>/<name>/<version>/setup.sh      (本版本)
                       <base>/<name>/setup.sh                 (稳定,每次 release 覆盖)
mjm:local:<dir>        同一目录结构,file: 写入,无网络
```

bootstrap `setup.sh` 下载 tar(curl,缺则 wget)→ 校验 sha256(`sha256sum` 或 `shasum -a 256`)→
解压到临时目录 → 执行包内 `setup.sh`;失败即退出并清理临时目录。URL 写入 bootstrap 时必须是最终可用地址。

**回读校验**:发布后必须从目标读回 tar(`local:` 读文件;`mjm:` GET;`gh:` GET asset,允许短暂重试),
sha256 与 manifest 一致才算成功;失败 exit 1 并报告具体 URL/文件。
