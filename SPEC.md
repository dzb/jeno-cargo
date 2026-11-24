# jeno 规范（SPEC）：文件与 CLI 契约

状态:现行;最后核对 2026-11-23

**SPEC 是规范;DESIGN 是理由;冲突以 SPEC 为准。** 本文定义文件格式、CLI 契约、发行物布局与
Maven 解码范围,是实现与验收的依据;取舍与替代方案见 [DESIGN.md](DESIGN.md)。

总原则:**拒绝式解析**。任何未在本 spec 中定义的节、键、值形态、语法糖 → 立即报错。
schema 封闭,拼写错误(`[dep]` ≠ `[deps]`)不会静默失效。

---

## 0. 四个文件

**名称**：本工具曾用前一代名称；旧文件、旧前缀与旧 `[repos] type` 不被读取，命令会给出改名/重发布提示（集中实现见 `LegacyNames`，行为见 §6）。

| 文件 | 谁写 | 谁读 | 提交版本库 |
|---|---|---|---|
| `jeno.toml` | 人 | jeno | 是 |
| `{artifact}-{version}.jeno.toml`(描述符) | `jeno publish` 生成 | jeno(消费方解析时) | 随发布物 |
| `jeno.lock` | jeno | jeno | 是 |
| `jeno.ws.toml` | `jeno init --ws` 或人 | jeno | **否**(本地文件,§5) |

---

## 1. TOML 子集文法

> 唯一实现:freeway-commons `com.jujin.freeway.commons.toml`(DESIGN D14)——jeno.toml、描述符、jeno.lock 与 freeway 的 `application.toml` 共用一份解析器。

```
document      = *( ws / comment / table / array-table / keyval )
comment       = "#" *( 任意非换行字符 )
table         = "[" dotted-key "]"                  ; [deps] [deps-dev] [deps-api] [limits] …
array-table   = "[[" dotted-key "]]"                ; 仅 jeno.lock 使用
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

## 2. jeno.toml

### 2.1 节与字段(closed schema)

**[project]**

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `name` | 字符串 | 是 | `^[a-z][a-z0-9-]*$`(用作 jeno 原生发布的 artifact 名) |
| `group` | 字符串 | 否 | 反向域名 id(`com.jujin8.jeno`)。写入描述符时它只是**标识事实**(`jeno info` 展示);jeno 只发布 jeno 布局,**不写 Maven 布局**,所以它不参与任何发布路径。消费 Maven 仓库时坐标仍是 `group:artifact`(读取侧,不变)。原生工件一律用工件名寻址,不存在 `group:artifact` 的形式 |
| `version` | 字符串 | 是 | jeno 原生发布时必须 semver(§2.4) |
| `main` | 字符串 | 否 | 主类全名。`jeno run` 缺参时的入口;`jeno package` 写进 MANIFEST 的 `Main-Class`;`jeno bundle` 写进 launcher |
| `summary` | 字符串 | 否 | 一句话说明。写入发布描述符,由 `jeno info` 展示 |
| `homepage` | 字符串 | 否 | 项目主页,**必须 `https://`**。写入描述符 |
| `license` | 字符串 | 否 | SPDX 标识符(`Apache-2.0`、`MIT`)。写入描述符 |

**[java]**

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `version` | 整数 | 否 | 目标 major 版本。编译:`javac --release N`(主码与测试码同目标);要求当前 JDK major **>= N**,否则构建失败。缺省 = 不设限 |

> 注:jeno 自身基于 Freeway(JDK 25+),运行 jeno 需要一个 ≥25 的 JDK;用户项目可通过 `--release` 目标更低版本。

**[deps]**

依赖节统一使用 **deps 前缀 + 后置限定词**:

```text
[deps]       生产依赖(主语)
[deps-dev]   测试依赖(限定词在后)
[deps-api]   导出 API 的直接依赖(限定词在后)
```

这是命名规则,不是嵌套关系。`[dev-deps]` 已不再接受,解析器会给出改名到
`[deps-dev]` 的迁移错误。旧的仓库分组表 `[deps.<repo>]`、`[deps-dev.<repo>]`
也已移除;仓库来源用值形态 `repo:version` 表达。

key = 坐标,value 形态决定物种:

| 值形态 | 物种 | 要求 |
|---|---|---|
| `"1.2.3"` | central | 版本字符串非空、无空白;**禁止 version ranges**(`[1.0,2.0)`)。带 classifier 的依赖沿 pom 传递时,classifier 是身份的一部分(`group:artifact:classifier`),URL 为 `artifact-version-classifier.jar` |
| `"corp:1.2.3"` | 命名仓库 | 只查 `corp` 这个仓库,**不回退**;`corp` 必须在 `[repos]` 里声明(否则报错并列出可用仓库名);版本规则与 central 相同(非空、无空白、禁 ranges/snapshot)。若 `corp` 是 Maven 布局 → 读 pom;若是 `jeno:` 仓库 → 读描述符(见下) |
| `"gh:owner/repo@tag"` | jeno 原生 | tag 为可选 `v` 前缀 + semver;展开规则见 §2.2 |
| `"https://…/x.jar"` | 裸 URL(叶子) | 必须 https、以 `.jar` 结尾 |

**`jeno:` 仓库**(`[repos] mine = "jeno:https://repo.example/"` 或 `"jeno:local:../vendor"`):jeno 自己的两层布局,发布物三件套
平铺在 `<base>/<name>/<version>/` 下(`<name>` = 发布方 `[project].name`),**不需要 `[project].group`**。
传输可以是 HTTPS(`jeno:https://…`)、字面私有网络 HTTP(`jeno:http://<private-ip>/…`,LAN 专用)
或本地目录(`jeno:local:<dir>`,`~` 展开、相对路径相对项目根,`jeno publish jeno:local:<dir>` 直接写入)。
依赖写成 `"<name>" = "mine:<semver>"`;元数据取自同目录的
`.jeno.toml` 描述符,`[project].name` 必须与 key 一致(由 `DescriptorReader` 校验)。描述符里的裸名+裸版本
指向同一仓库的兄弟工件(描述符只写事实,不写仓库规则)——**这条兄弟规则仅适用于从 `jeno:` 仓库取到的
描述符**;从 `gh:` 取到的描述符里写裸名不会变成同仓库,仍按 Central 坐标报错。`jeno:https://` 与
`jeno:local:` 都支持;`http://` 只接受**字面私有网络地址**(localhost/127/8、10/8、
172.16-31、192.168/16、169.254/16、IPv6 ::1/fc00::/7/fe80::/10),公网 http 与主机名一律拒绝;
store 索引与 `--offline` 行为不变。

**歧义规则**(无例外):

1. `gh:` / `https://` 前缀**先**匹配——它们是保留前缀,代表 jeno 自己的物种。
2. 其余值,若冒号前是**已声明**的仓库名(且冒号后非空),按「命名仓库」解析;声明了却没写对名字、
   或名字未声明 → 报错并列出可用仓库名(不默默当成 central 版本)。不匹配仓库名形式的(例如
   `1.2.3`)仍是 central 版本。

`gh` 与 `gitee` 是保留的仓库名:`[repos] gh = …` / `[repos] gitee = …` 直接报错。
`gh:` 是物种前缀;`gitee:` 虽已移除,但它的值必须继续产出迁移错误,不能被一个同名仓库静默接管。
`local` / `http` / `https` / `file` 不保留。

> **`gitee:` 已移除(2026-11-22)**:Gitee 的 release 附件即使匿名下载也要令牌,违反“零配置”原则。
> 替代写法:直接写 release jar 的 `https://…jar` 叶子,或发布到自己的主机并在 `[repos]` 声明后用
> 值形态引用。遇到 `gitee:` 会报错并给出这两种写法。

- 仓库分组表 `[deps.<repo>]` / `[deps-dev.<repo>]` 已移除;出现即报错并提示改用 `repo:version`
- 命名仓库必须先在 `[repos]` 中声明,值形态中的仓库名未声明 → 报错

**[deps-api]** — 导出的 API 依赖标记(可出现在 jeno.toml 与 jeno-native 描述符)

`[deps-api]` 不是另一张依赖声明表,而是对 `[deps]` 中已有直接依赖的**API
可见性标注**:

- 每个条目必须与 `[deps]` 中的同 id 声明逐字段完全一致(相同 source/species/version/tag)
- `[deps]` 必须在源文件中物理地出现在 `[deps-api]` 之前;没有前置 `[deps]` 直接报错
- 不允许把 `[deps-dev]` 或 processor 标成 API;API 只表达消费者编译可见性
- 发布的描述符携带同样的 `[deps-api]` 标记
- 消费者编译 classpath = 自己的 MAIN 声明 + 沿显式 API 边可达的闭包;
  普通 transitive implementation 依赖仍只进 runtime classpath
- Maven POM 没有 API/implementation 区分,不会自动推断;需要导出的 POM 依赖由作者
  在自己的 jeno manifest/描述符中显式声明

**[deps-dev]** — 仅测试可见的依赖(Cargo `dev-dependencies`、uv `[dependency-groups]` 先例)

独立顶层节,命名遵循“依赖在前、修饰在后”。声明规则与 `[deps]` **完全一致**
(同样的物种形态、同样的值形态来源、同样的精确版本约束),差别只在可见性:

| 面 | `[deps]` | `[deps-dev]` |
|---|---|---|
| 编译可见性(strict visibility) | ✅ | ❌ 从不出现在 javac 参数 |
| `jeno run` 运行时 classpath | ✅ | ❌ |
| `jeno package` / `jeno bundle` 产物 | ✅ | ❌ |
| 发布描述符(传播给消费者) | ✅ | ❌ **绝不外泄** |
| `jeno test` 编译与运行 classpath | ✅ | ✅ 这就是它存在的理由 |
| jeno.lock | ✅ 行无 scope | ✅ 行带 `scope = "dev"` |
| 深度预算 / 跨 major 冲突检测 | ✅ | ✅ 同等对待 |

- 同一个 id 同时出现在 `[deps]` 与 `[deps-dev]` → 报错(一个声明一个作用域)
- 传递性作用域:仅从 `[deps-dev]` 可达的工件一律 `dev`;既能从 `[deps]` 也能从 `[deps-dev]` 可达的
  工件为 `main`(**生产用途胜过测试用途**——同 Cargo 规则)
- 读兼容:jeno.lock 中缺省 `scope` 即 `main`,所有 `[deps-dev]` 之前的锁文件原样可读

**[repos]**

每个仓库是一张子表,**类型决定布局**:

```toml
[repos.corp]          # Maven 布局(缺省 type 可省)
url = "https://nexus.corp/"

[repos.mine]          # 同一台静态主机,也可以是 jeno 布局
type = "jeno"
url = "https://my.host/"

[repos.vendor]        # 裸路径 = 本地目录(~ 与相对项目根均可)
type = "jeno"
url = "vendor"
```

| 字段 | 规则 |
|---|---|
| key | 仓库名,`^[a-z][a-z0-9-]*$` |
| `type` | `maven` \| `jeno`(封闭枚举;缺省 `maven`) |
| `url` | `https://…`、私有网络内的 `http://<literal-ip>…`(不做 DNS;公网/主机名 http 拒绝)或文件系统路径。以 http 开头即 URL,否则即目录 |
| `insecure` | 可选布尔,缺省 false。仅允许 `type = "jeno"` 且 `url` 为私有网络 `http://…`;显式允许 `JENO_PUBLISH_TOKEN` 发往这一仓库的明文连接。HTTPS/本地目录/maven 布局不得设置 true |

| 布局(由 `type` 决定) \ 位置(由 `url` 决定) | 网络(https 或私有 http) | 本地路径 |
|---|---|---|
| **`type = "maven"`** | `https://host/…` 或 `http://192.168.1.39/…` | `url = "vendor"` |
| **`type = "jeno"`** | `url = "https://host/"` 或私有 `http://…` | `url = "vendor"` |

布局是声明的属性,不是 URL 的属性:同一台 https 主机可以装任一布局,所以协议必须由 `type`
声明,不能从 URL 前缀猜。子表里只允许 `type`、`url`、`insecure` 三个键,未知键报错(带
`file:line:column`)。`insecure` 是仓库级写入策略,不进入锁的 `source` 声明原文。

**legacy 字符串写法**(仍可读,等价于上表):
`"https://…"` = maven+https;`"http://<私有 IP>…"` = maven+私有 http;`"local:<dir>"` = maven+本地;
`"jeno:https://…"` = jeno+https;`"jeno:http://<私有 IP>…"` = jeno+私有 http;`"jeno:local:<dir>"` = jeno+本地。
写锁时这些写法都归一成同样的规范声明,锁格式不变。

查找顺序:**互不回退**——值形态 `repo:version` 只查对应仓库,裸版本只查 Central。
`local:` 仓库的字节同样经过 store 的 sha256 钉定,与远程仓库同等可审计。

**[limits]**

| 字段 | 规则 |
|---|---|
| `max-depth` | 整数,可选,范围 1–255,**缺省 2**(默认即哲学)。0 与负数 → 报错 |

**[overrides]**

- key = 依赖 id(引号字符串:`group:artifact`、jeno 原生 artifact name、GH/repo name 或 URL name),value = 版本字符串
- central 物种:任意 Maven 版本字符串;jeno 原生物种:必须 semver;URL 物种无版本概念 → 其 id 出现在此 → 报错
- 语义:MVS 之上的显式钉定——收敛时该 id 直接取 override 版本,忽略其他来源的版本约束;跨 major 硬冲突在 override 应用**之后**检查(它是合法出口)
- 解析后闭包中不存在该 id → 告警(提示清理残留声明)
- 被 override 的 id 必须在 `jeno tree` / `jeno why` 输出中标注
- value 为 version ranges → 报错(与 deps 一致)

**[scripts]**

- key = 任务名,value = 命令字符串
- 执行方式:经系统 shell(`sh -c`),工作目录 = 项目根,与 npm scripts 一致
- `jeno run <script-name>` 优先于 `<main-class>`:先查 [scripts],命中则执行脚本;未命中才走 java 主类路径
- **注入环境变量**(脚本据此启动项目,无需自己拼 classpath):

  | 变量 | 值 |
  |---|---|
  | `JENO_CLASSPATH` | `target/classes` + 闭包中所有 MAIN jar,path-separator 连接。缺 build 产物或 jeno.lock 时不设置(脚本仍运行) |
  | `JENO_HOME` | 项目根目录的绝对路径 |

  例:`launch = "java -cp \"$JENO_CLASSPATH\" com.example.Main"`
- **额外参数**以位置参数传入:`jeno run task a b` → `sh -c "<command>" task a b`,命令里用 `"$1"` `"$2"` 取值(第一个操作数是 shell 的 `$0`,被脚本名占位)
- 脚本退出码即 `jeno run` 的退出码

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

**[toolchain]**

| 键 | 类型 | 规则 |
|---|---|---|
| `jars` | 字符串数组 | 测试/构建工具链工件的 id 清单。每个 id 必须已出现在 `jeno.lock` 的闭包中;不得重复。 |

这是**packaging metadata,不是依赖作用域**:

- 不参与 `Resolver`,不改变 strict visibility、`[deps-dev]` 语义或 lock 解析;
- `jeno bundle` 永不把这些 id 合并进 launcher jar;
- `jeno bundle --toolchain` 把它们原样复制到 `lib/toolchain/`(本地 workspace 替换时打包对应 classes),默认不带;
- `jeno lock` 后发现 id 不在锁图 → `bundle` 报错并提示重新 `jeno lock`;
- 清单里的工件可以来自 MAIN、DEV 或 PROCESSOR 的锁闭包;PROCESSOR 不会被合并进 launcher jar,但列入清单且带 `--toolchain` 时会复制到 `lib/toolchain/`。

未知节、未知键 → 报错。

### 2.2 值形态展开(jeno 原生)

```
gh:{owner}/{repo}@{tag}
  → https://github.com/{owner}/{repo}/releases/download/{tag}/{repo}-{version}.jar
  → 及同名 .jeno.toml 与 .sha256
```

`{version}` = tag 去 `v` 前缀。

> `gitee:` 曾在 2026-11-21 按同样的形状实装,2026-11-22 移除:附件匿名下载返回 403(需 Bearer
> 令牌),与“声明即用”相悖。需要 Gitee 上的构件时,写裸 URL 叶子或在自建主机上用 `[repos]`。

### 2.3 深度预算(语义,细节见 DESIGN.md §4.2)

- compile/runtime 闭包计入预算；`test-scope` 指 **Maven pom 的 test/provided/system scope**(解码时即丢弃,不进图)
  jeno 自己的 `[deps-dev]` **同等计入预算**(它就是 `[deps]` 的另一个可见性分组,不是 scope 豁免)——见 §2.1 对照表
- MVS 收敛后的最终图上计数;**深度 = 从声明根出发的最长引入路径**(预算的真意是暴露最深的链——最短路径会被旁路掩盖);**依赖环 = 硬错误**(环让"最深链"无定义,与根治依赖地狱同向)
- 超限 → 硬错误;出口 = 替换库 / exclude / 调大 max-depth;**无豁免机制**

### 2.4 版本规则与排序(语义感知 MVS)

- central 物种:任意 Maven 版本字符串;排序采用 Maven `ComparableVersion` 语义
- jeno 原生物种:tag 去 `v` 前缀后必须 semver(`X.Y.Z`,可带 `-prerelease`);`jeno publish` 时校验
- **MVS 收敛规则:**
  - 同 id 同 major → 取最大版本,与声明顺序无关
  - 同 id 跨 major(jeno 原生物种)→ **硬冲突报错**,错误信息给出完整引入路径;出口 = 统一版本或 `[overrides]`(§2.1)。理由:跨 major 静默取大会让低 major 使用方运行期炸 `NoSuchMethodError`——正是 jeno 要根治的沉默地狱
  - central 物种 best-effort:版本形如 semver 时按 major 规则;非 semver 形态(如 `2023.01`、`30.0-jre`)退回全局 max-wins
- version ranges(`[1.0,2.0)`)在 pom 解码与 jeno.toml 中均报错(拒绝式解析)
- **retract 跳过**:描述符 `[retract]` 声明的版本(§3.4)不参与"取最大";消费方显式钉定被撤回版本 → 允许但输出告警(显式选择优先于作者撤回)

---

## 3. 描述符 `{artifact}-{version}.jeno.toml`

仅用于 **jeno 原生物种**(Maven-layout 仓库的元数据是 pom,不用描述符)。
发布产物三件套:`{name}-{version}.jar` + `{name}-{version}.jeno.toml` + `{name}-{version}.sha256`。

### 3.1 字段(closed schema)

```toml
[project]
name = "mylib"            # 必填;必须与文件名 artifact 段一致,否则报错
group = "com.example"     # 可选;标识事实,由 jeno info 展示;原生工件用工件名寻址,无 group:artifact
version = "1.2.0"         # 必填;必须与文件名 version 段一致,否则报错
summary = "..."           # 可选;一句话说明这个库是做什么的(供 jeno info 展示)
homepage = "https://..."  # 可选;必须 https;项目主页
license = "Apache-2.0"    # 可选;SPDX 标识符

[java]
version = 21              # 可选;语义同 §2.1 [java]

[deps]                    # 可选;物种仅限 central / gh: / URL
"com.example:foo" = "1.2.3"

[deps-api]                # 可选;必须是 [deps] 中已有声明的 exact 副本
"com.example:foo" = "1.2.3"
```

### 3.2 禁止项(出现即报错)

`[repos]`、`[deps.<repo>]`、`[deps-dev]`、`[limits]`、`[scripts]`、`[overrides]`、`[toolchain]`、任何未知节;
`[deps]` 与 `[deps-api]` 是允许的事实表。旧的 `[deps.<repo>]` 分组表一律拒绝。

理由:仓库是消费方的配置;预算规则只有一份(消费方);scripts 只属于应用。**发布物里只有事实,规则只有一份。**

### 3.3 语义

描述符 `[deps]` 参与消费方闭包与深度预算,与消费方直接依赖同等对待。
`[deps-api]` 标记其中哪些依赖通过 API 闭包进入消费者的编译 classpath;普通
descriptor 依赖仍只进 runtime classpath。

`[project]` 的 `summary` / `homepage` / `license` 是**标识事实**:发布后不可变、不参与解析、
仅由 `jeno info` 读取展示。它们与 `[deps]` 同属"发布物里的事实",不是规则。

### 3.4 [retract](可选节,吸收自 Go modules)

```toml
[retract]
"1.2.0" = "引入 #121 回归,请用 1.2.1"
```

- key = 被撤回的 semver 版本,value = 理由字符串(必填,告警信息会引用它)
- 解析器在"取最大"时跳过被撤回版本(§2.4);消费方显式钉定被撤回版本 → 允许但告警
- 撤回是事后声明:`jeno publish` 发布某版本时,该版本自身不得出现在 `[retract]` 中;发布新版本可顺带撤回旧版本

---

## 4. jeno.lock

### 4.1 结构(closed schema)

```toml
# 由 jeno 生成,提交进版本库,勿手改
# jeno-lockfile-version: 2
# 旧的无 marker 文件按 version 1 读取;未来 > 当前支持的版本直接拒绝
# api = true 只在通过显式 [deps-api] 边可达时出现
[[artifacts]]
id = "com.example:foo"    # central: "group:artifact";jeno native: artifact name
version = "1.2.3"
source = "central"        # "central" / "gh:owner/repo" / "url"
                          # / 命名仓库的声明原文(如 "local:vendor"、"jeno:https://repo.example/"、"jeno:local:../vendor")
url = "https://…"         # 实际下载 URL。local: 仓库写为相对项目根的 file: 形式
                          # (如 file:vendor/com/…/x.jar),读取时按项目根展开;
                          # 路径落在项目根之外(如 local:/opt/repo)仍写绝对 file:///…;
                          # 旧的绝对 file:/// 锁行照旧可读
sha256 = "…"              # artifact 字节(jar),64 位十六进制,必填
meta-sha256 = "…"         # 元数据字节(pom.xml 或描述符);URL 叶子无元数据,省略该字段
depth = 1                 # 实际深度,>= 1
scope = "dev"             # 可选:[deps-dev] 可达的工件。缺省 = main
                          #       另一取值 "processor"(§2.1 [build].processors)
api = true                # 可选;通过 [deps-api] 进入编译可见性的 API 闭包
```

### 4.2 不变式

1. id 在闭包内唯一(MVS 单版本);同一 id 不得来自两个不同 source,同一 source 也不得以两个 id 重复出现
2. sha256 与 meta-sha256 必为 64 位十六进制(URL 叶子无元数据,meta-sha256 缺省);**每次下载都强制核对,任一不匹配即失败**(release asset 可变风险由它们兜底;元数据变了依赖图就变了——go.sum 同时哈希 zip 与 go.mod 的先例)。`local:` 仓库与远程同等钉定
3. url 必须 https;明文 http 仅限字面私有网络地址(localhost、127/8、10/8、172.16-31、192.168/16、169.254/16、IPv6 ::1/fc00::/7/fe80::/10),公网 http 与主机名拒绝
4. depth ≥ 1
5. 按 id 字典序输出(稳定 diff)
6. 一致性:lock 的依赖集合与 jeno.toml + 描述符闭包一致;不一致 → 拒绝 build/run,提示 `jeno lock`。`tree`/`why` 也以锁为权威:二者先读锁、再解析 manifest 只为补父子边,id/version/source/depth/scope/api 任一不一致即 exit 1(至多 10 行差异,不输出图);无锁 → exit 1
7. 命中策略:lock 有效 → 直接采用(sha256 权威,不重新解析);`jeno lock` **总是重解**并重写锁,
   `--check` 只比较
8. 兼容性:旧锁里 `source` 以 `gitee:` 开头的行,**读锁即报**可执行的迁移错误(gitee: 物种已移除),而不是留给下游命令各自报 URI 错误;`source` 以 `jeno:` 开头的行照常读
9. 版本 marker:`# jeno-lockfile-version: 2` 是注释,不是 TOML key;旧版 jeno 可忽略它,新版对高于自己支持的版本直接拒绝。无 marker 的旧锁按 version 1 读取

---

## 5. `jeno.ws.toml`(本地工作区文件)

列出同一目录树里的若干模块,让彼此依赖时用本地的 `target/classes` 取代已发布的 jar。
**只影响字节来源,不影响解析与锁**(设计记录见 `docs/workspace-design.md`)。

```toml
[ws]
modules = ["libs/core", "apps/web"]
```

### 5.1 字段(closed schema)

| 节 | 键 | 类型 | 规则 |
|---|---|---|---|
| `[ws]` | `modules` | 字符串数组,必填 | 相对本文件所在目录;每一项必须是一个**存在且含 `jeno.toml`** 的目录 |

- 没有其他节、没有其他键。任何未列出的节/键 → 报错(`拒绝式解析`)。
- 路径接受绝对路径,但生成工具总写相对路径(正斜杠、字典序)。
- `modules = []` → 解析错误:本 TOML 子集不支持空字符串数组(§1)。
  (`jeno init --ws` 扫不到模块时也会拒绝写文件——它不生成一个空工作区。)

### 5.2 语义

- **查找**:从当前目录向上找最近的 `jeno.ws.toml`;没有则按单模块工作(与本文档其余部分完全一致)。
- **根不能是模块**:同一目录同时含 `jeno.toml` 与 `jeno.ws.toml` → 报错。
- **重复**:两个条目指向同一 realpath → 报错;两个模块产出同一个 id → 报错。
- **拓扑序**:模块间的依赖关系推出执行顺序;成环 → 报错并给出环。
- **不提交**:这是本机文件(git 由用户管,jeno 不写 `.gitignore`、不检查跟踪状态)。
- **不参与发布**:发布物只描述已发布版本;本地 classes 的替换不写进锁、不写进描述符。

### 5.3 与锁的关系

`jeno.lock` 的格式与语义**完全不变**:每个模块仍各有一份锁,锁里仍是从仓库解析出来的
工件(sha256 权威)。替换发生在装配 classpath 时,因此锁在任何机器上都一致。

---


## 6. CLI 契约

### 6.1 命令

22 个命令,以本表为准(`jeno --help` 只打印同一份清单的摘要)。

| 命令 | 契约 |
|---|---|
| `jeno init [--pom] [--ws] [--force] [paths...]` | 写 `jeno.toml` 骨架;`--pom` 从 `pom.xml` 迁移并逐条报告不可表达的事实——本地 parent 从 `relativePath` 读取,本地不存在时经 store 从 Central 读取远端 parent 与 `scope=import` 的 BOM(递归其 parent/嵌套 BOM,`--offline` 只用已缓存内容,深度上限 8);快照/私有仓库 BOM 不自动解析;`[java].version` 从 `maven.compiler.release`/`java.version`/`maven.compiler.target`/`source` 推导;`--ws` 写 `jeno.ws.toml`(§5),此时 `paths...` 可显式指定模块目录。**工作区根目录拒绝 `init`/`--pom`/`--force`**(exit 1),`--ws` 仍可用 |
| `jeno add <key> [value] [--repo <name>]` | key: `group:artifact`(Central)、裸名(搜索)、`owner/repo@tag`(原生简写)。value: `1.2.3`、`<repo>:1.2.3`、`gh:owner/repo@tag`、裸 `https://...jar`。无 value 时:`group:artifact`/裸名只查询,不写 `jeno.toml`(查询成功 exit 0);`--repo <name>` 读取该 named `jeno:` repository 的 `<base>/<name>/index.jeno.toml` 并列出版本;`owner/repo@tag` 会写入 `[deps]` 并重锁 |
| `jeno remove <id>` | 删除一行声明并重锁 |
| `jeno lock [--check]` | 解析并写出 `jeno.lock`;**总是重解**。`--check` 只在内存生成并与磁盘逐字节比较,不写文件。未知参数是用法错误(exit 2) |
| `jeno build` | javac 编译 `src/main/java`,严格可见性 classpath(只含 MAIN 声明);`--release` 取 `[java].version`;processor 走 `-processorpath`。至少一个 Java 源或 `src/main/resources` 下的文件必须存在:有源则编译并拷资源;无源但有资源则只创建 `target/classes` 并拷资源、exit 0;两者都没有则报错 |
| `jeno test` | fork JVM:`@JenoTest` + JUnit Platform;classpath 含 MAIN + dev,processor 只走 javac 的 `-processorpath`;每次运行先清空 `target/test-results/`(空测试/编译失败也会清空),XML 报告写该目录 |
| `jeno run [<main>] [args…]` | 在 runtime classpath(排除 dev)上运行;无参数时用 `[project].main`;`[scripts]` 优先于同名主类。target 在 `--` 之前(可省略);jeno 旗标只在 target 之前生效;`--` 之后**全部**是程序参数(§6.2) |
| `jeno tree [--json]` | 依赖树 + 深度(processor 根也会列出并标 `, processor`);显式 `[deps-api]` 闭包标 `, api`;**以 `jeno.lock` 为准**:先读锁,再解析 manifest 只为补父子边;id/version/source/depth/scope/api 任一不一致 → exit 1、至多 10 行差异、不输出图 |
| `jeno why <id>` | 每个引入路径与深度;API 闭包中的 artifact 标 `, api`;同样以锁为准,漂移拒绝画图 |
| `jeno info <id> [--source]` | 身份/来源/依赖/源码地址;`--source` 取源码树。标题按 scope 标记:`[dev]`、`[processor]`,MAIN 无标记 |
| `jeno classpath [--test] [--file <path>]` | IDE 用 classpath;默认编译可见性;`--test` 为测试闭包(MAIN + dev,**排除 processor**——与 `jeno test` 的差异见 REMINDS §14);路径指向 store 内容寻址 jar,不拷贝 |
| `jeno package [--uber] [--main <class>]` | `target/dist/<name>-<version>.jar`;`--uber` 合并闭包输出 `...-uber.jar`;`--main` 只影响本 jar 的 MANIFEST,不影响 `bundle`/`run` 的 `[project].main` |
| `jeno bundle [--toolchain]` | `target/dist/<name>-<version>/`(bin/<name>、bin/<name>.cmd、lib/、install.sh、uninstall.sh、SHA256SUMS)+ `.tar.gz`。需要 `[project].main`(缺失即报错);install.sh 的 JDK 要求取 `[java].version`,未声明则取构建 JDK major;安装前用 `sha256sum -c`/`shasum -a 256 -c` 校验 SHA256SUMS,lib/bin 经 staging 交换并保留 store/config;默认不带 toolchain,`--toolchain` 才把 `[toolchain].jars` 对应的锁工件写入 `lib/toolchain`;未在锁图中出现的 toolchain id 是错误 |
| `jeno clean [--target (default) \| --dist]` | 默认整棵删 `target/`;`--dist` 只删 `target/dist/` 保留 classes;永不触碰 store |
| `jeno cache dir \| size \| prune \| purge [--dry-run] [--yes]` | `dir`/`size` 只读;`prune` 删可证明无用之物,无需确认;`purge` 删 store 与 sources,是**破坏性**命令——无 `--yes` 时打印路径/大小并 exit 2,`--dry-run` 只展示不删除(exit 0),`--yes` 才执行;`clean` 是兼容 alias,支持同样 flags |
| `jeno outdated [--json] [--dev]` | **只读 `jeno.lock`**(不需要 `jeno.toml`);已钉版本 vs 仓库可用版本;默认只查 MAIN,`--dev` 含 dev/processor;Central 用 `maven-metadata.xml`,named `jeno:` 用 `<base>/<name>/index.jeno.toml`;索引缺失的 jeno row 列为 `not checked`;`gh:`/`url:`/`local:` 仍不可检查;跨 major 单列;完成即 exit 0(有更新也是 0) |
| `jeno doctor` | **只读**本地诊断:检查当前项目或 workspace 每个模块的 `jeno.toml`/`jeno.lock` 是否存在且可解析,报告旧名文件(jeno **不会**读取它们,命令会给出改名提示),并检查 XDG store/sources/locks 路径可写性;不解析依赖、不写文件、不访问网络。健康 exit 0,有问题 exit 1,带参数 exit 2 |
| `jeno audit [--json] [--dev]` | OSV 漏洞查询;默认只查 MAIN,`--dev` 才含 dev/processor;只查 Central 坐标,其余列 `notChecked` 并给原因;漏洞或查询失败 exit 1 |
| `jeno verify` | **只读本地发布预检**:解析 `jeno.toml`/`jeno.lock`,核对每一条直接声明与锁定行的 id/version/source/MAIN scope;按 sha256 校验 store 中每个 jar 与元数据(workspace 本地替换则校验 classes 与版本戳);若 `target/dist/` 已有完整发布三件套,校验其内部一致性与 bundle `SHA256SUMS`;只有 package 产物(仅 jar)视为未准备(skipped),部分三件套仍失败;bundle 目录存在但缺 `SHA256SUMS` 也失败(`artifacts.bundle-incomplete`)。不解析依赖、不下载、不修改项目文件、不访问网络。通过 exit 0,失败 exit 1,带参数 exit 2 |
| `jeno publish [target] [--json] [--dry-run]` | 写三件套;目标只接受 `gh:`、`jeno:https:`、私有网络 `jeno:http://<literal-ip>…`、`jeno:local:`(§6.6)。jeno 布局逐文件 PUT 后立即 GET 回读,三个文件的字节必须完全一致,否则 exit 1;`--dry-run` 不写 descriptor/sha256、不创建目标、不上传 |
| `jeno upgrade` / `jeno update`(alias) | 自升级(同一实现):只认 `jeno-<version>.tar.gz` + `.sha256`,下载后先校验 sha256;Windows 执行 release 的 `install.ps1`,POSIX 解压后执行包内 `install.sh` |
| `jeno release [target] [--json] [--dry-run] [--allow-unsigned] [--notes-file <path>]` | 发布 `jeno bundle` 产生的安装包(默认用 minisign 签名,`--allow-unsigned` 才允许无签名):`gh:<owner>/<repo>`、`jeno:https://host/`、私有网络 `jeno:http://<literal-ip>…`、`jeno:local:<dir>`;无 target 只准备 `target/dist/release/` 并打印上传清单。**不隐式 build/test/bundle**;发布后回读并校验 tar 的 sha256(§7.3) |

### 6.2 参数约定

- **未知参数是用法错误**:exit 2,不静默忽略(`build`/`test`/`bundle`/`upgrade`(alias `update`) 无参数;`why` 恰好一个 id;`publish` 最多一个 target)。
- **`--` 结束 jeno 的选项**:之后的内容作为位置参数交给命令(`run` 交给程序)。
- **项目锁**：写 `target/`/`jeno.lock` 的命令 `lock`、`build`、`test`、`clean`、`add`、`remove`、`package`、`bundle`、`publish`、`release` 共用一把项目锁；`publish`/`release` 的上传阶段也在锁内。
- **`--help` / `-h`**:只在 `--` 之前、且是唯一剩余参数时生效;`jeno --help` 打印清单 exit 0。
- **`run` 的旗标边界**:jeno 的 `--module`/`--offline`/`--locked` 只在 target 之前生效;target 之后(或 `--` 之后)全部是程序参数。
- **`--locked`**:命令前后均可;关闭 workspace 的本地 `target/classes` 替换,classpath/verify/package/bundle 只消费 `jeno.lock` + store。

### 6.3 退出码

| 码 | 含义 |
|---|---|
| 0 | 成功;`outdated` 完成(有更新也是 0);`audit` 无漏洞;`tree`/`why` 一致 |
| 1 | 失败;`audit` 有漏洞;`lock --check`/`tree`/`why` 漂移;查询失败 |
| 2 | 用法错误 |

本表描述 jeno 自身的退出码。`run` 会把程序/脚本的退出码原样返回;`test` 会把 fork 出的测试 JVM 的
退出码原样返回。`why` 找不到 id 是 0(有效的否定答案);`info` 找不到 id 是 1。

### 6.4 工作区策略

| 策略 | 命令 | 根目录行为 |
|---|---|---|
| orchestrate | `lock`、`build`、`test`、`package`、`bundle`、`clean` | 按依赖拓扑序逐模块;`--module <path>` 只跑一个;`build`/`package`/`bundle`/`lock` 遇错即停,`test`/`clean` 继续并汇总 |
| requireModule | `add`、`remove`、`run`、`classpath`、`info`、`tree`、`why`、`publish`、`audit`、`outdated` | 无 `--module` → exit 2 标准句;带 `--module` → 在该模块执行 |
| global | `init`、`cache`、`upgrade`(alias `update`) | 不参与编排;`init` 在根目录拒绝(§6.1) |

### 6.5 输出与 JSON

**人类可读输出**可以按终端能力着色、加状态符号并分组帮助页;这是展示层,
不改变命令语义。非 TTY(管道、CI、测试 harness)必须保持无 ANSI 的纯文本。
`JENO_COLOR=always|never|auto` 可显式覆盖检测,`NO_COLOR` 的存在表示禁用;
颜色永不进入 `--json` 的 stdout。

#### 6.5.1 Agent 能力目录

`jeno --json --help` 输出完整 `commandCatalog`;`jeno --json <command> --help`
输出单个 `commandDescriptor`。catalog 由每个 `Command.capabilities()` 生成,
不得另维护一份命令能力清单。根字段:

| 字段 | 类型 | 说明 |
|---|---|---|
| `schemaVersion` | 整数 | 当前为 `2`;破坏性变更必须递增 |
| `kind` | 字符串 | `commandCatalog` / `commandDescriptor` / `commandResult` / `version` |
| `jenoVersion` | 字符串 | 当前 jeno 版本 |
| `commandCount` | 整数 | catalog 中的主命令数(不含 alias) |
| `commands[]` | 数组 | 按 category/order/name 稳定排序 |
| `globalFlags[]` | `{name, description}` | 全局参数 |
| `exitCodes` | 对象 | `0/1/2` 的稳定含义 |

每个命令 descriptor 至少包含:

`name, aliases, category, summary, usage, help?, supportsJson, network,
writes, workspace, supportsDryRun, destructive, confirmation, passThrough,
interactive`。

- `network`: `never | optional | always`
- `writes`: `project | target | cache | sources | locks | user-install | remote`
- `workspace`: `global | module | aggregate`
- `confirmation`: `none | conditional | requires-yes | requires-signature`

`jeno --json --version` 输出 `kind=version` 的 JSON。对声明
`supportsJson=false` 的命令请求 `--json` 时,stdout 必须只有一个结构化
`JSON_UNSUPPORTED` 错误,exit 2;不得先打印人类文本。

支持 `--json` 的命令:`tree`、`outdated`、`audit`、`doctor`、`verify`、
`lock`(普通 lock 和 `--check`)、`why`、`classpath`、`info`、`cache`
(含 `dir`/`size`/`prune`/`purge`)、`build`、`package`、`bundle`、`clean`、
`init`、`add`(写入模式)、`remove`、`upgrade`、`publish`、`release`、`test`。
stdout 只有**一个** JSON 文档,诊断/警告走 stderr。runner 还会兜底:JSON 命令
若提前失败而未输出结果,stdout 仍会得到一个 `COMMAND_RESULT_MISSING` 或
`COMMAND_FAILED` 结构化错误。唯一明确不提供 JSON 的是 `run`:它的
stdout/stderr/退出码属于用户程序,`jeno --json run` 会被 runner 以
`JSON_UNSUPPORTED` 拒绝;`jeno run app --json` 则把 `--json` 透传给程序。

所有 `commandResult` 根对象共享:

| 字段 | 类型 | 说明 |
|---|---|---|
| `schemaVersion` | 整数 | 当前 `2` |
| `kind` | 字符串 | `commandResult` |
| `command` | 字符串 | 命令名 |
| `status` | 字符串 | `ok` / `failed` / `error` |
| `exitCode` | 整数 | 与进程退出码一致 |
| `dryRun` | 布尔 | 本次调用是否为 dry-run |
| `offline` | 布尔 | 本次调用是否处于 offline 模式 |
| `diagnostics[]` | 数组 | `{severity, code, message, nextActions[][]}` |

`doctor` 和 `verify` 的 workspace 结果统一为:

```text
workspace: {root, mode: single|module|all|unknown}
modules[]:  {module, path, status, exitCode, project?, checks[]}
summary:    {modules, passedModules, failedModules, checks, ...}
```

`unknown` 只由 `doctor` 产生（`jeno.ws.toml` 存在但读不了）；`doctor` 不接受 `--module`。

`lock --check` 使用同样的 `workspace/modules/summary` 外壳;每个 module 额外有
`lockFile`, `check`, `upToDate`, `artifacts`, `differences[]`。check 漂移时
诊断码为 `LOCK_STALE`,并通过 `nextActions` 给出 `["jeno","lock"]`。

`why --json` 输出 `{found, target?, artifact?, paths[][], pathEdges[][], introductionPaths}`,
`artifact` 含 `api` 标记;`pathEdges` 与每条 `paths` 对齐,元素为
`{from,to,api}`,用于区分普通依赖边与显式 API 边;
不在图中时 `found=false` 且 exit 0。`classpath --json` 输出
`{mode: compile|test, classpath, entries[], file?, written?}`。

`info --json` 输出消费视图:
`{artifact:{id,group?,artifact,version,sha256,metaSha256?},
provenance:{source,url,readableUrl,repository?,layout,transport?,jar,metadata?},
descriptor?:{name,group?,version,javaVersion?,summary?,homepage?,license?,
dependencies[{id,species,version?,tag?,repository?,classifier?,api}],retract?},
context:{scope,depth,local,marker},
source?:{page?,archive?,fetched}}`。
其中 `artifact/provenance/descriptor` 是包事实,`context` 只描述当前项目的 lock。
`info --source --json` 目前以 `JSON_SOURCE_UNSUPPORTED`(exit 2)明确拒绝;
本地 `info --json` 不写 sources tree、不触发源码下载。

`cache --json`:
- `dir` → `{paths:{store,sources,locks}}`
- `size` → `{sizes:{store:{bytes,human},...,total:{bytes,human}}}`
- `prune` → `{subcommand:"prune",removedFiles,reclaimedBytes,reclaimed}`
- `purge --dry-run` → `{subcommand,dryRun:true,requiresYes,roots[]}` 且不删除
- `purge` 无 `--yes` → `CONFIRMATION_REQUIRED`,exit 2,不删除;
  `purge --yes` → `{freedBytes,freed,removed[]}`。

`build`/`package`/`bundle`/`clean` 使用与 workspace 检查命令一致的
`workspace/modules/summary` 外壳;每个 module 的 `result.artifacts[]` 为:

```text
{kind, path, sha256?, bytes?, fileCount?, variant?, mainClass?}
```

- `build`: `kind=classes`,`fileCount`,`bytes`;不提供单文件 sha256
- `package`: `kind=jar`,`variant=plain|uber`,`sha256`,`bytes`,`mainClass?`
- `bundle`: `kind=bundle-directory`(fileCount/bytes)以及
  `kind=tar.gz`(sha256/bytes);`result.files` 给出 launcher/setup/uninstall/checksums
- `clean`: `result.removed[]` 每项含 `kind=target|dist,path,bytes,fileCount`;
  另有 `removedCount` 和 `nothingToClean`
- 失败 module 在根 `diagnostics[]` 有稳定码(如 `BUILD_FAILED`、
  `PACKAGE_FAILED`、`BUNDLE_FAILED`、`CLEAN_FAILED`)及 exit code

`init --json` 输出 `{mode:project|pom|workspace,file,overwritten,
project?:{name,version},moduleCount?,modules[]?,nextActions}`。
`add --json <key> <value>` 输出 `{mode:"mutate",dependency:{id,value},
manifest,lock,artifactCount,lockUpdated}`;无 value 的远程发现模式以
`JSON_DISCOVERY_UNSUPPORTED`(exit 2)明确拒绝,不混入人类列表。
`remove --json <id>` 输出 `{mode:"mutate",removed:{id},manifest,lock,
artifactCount,lockUpdated}`。
`upgrade --json [--check]` 输出 `{checkOnly,currentVersion,latestVersion,
updateAvailable,upgraded,takesEffectNextInvocation}`;`--check` 不下载、不执行
install.sh、不修改安装目录。

`publish --json [target] [--dry-run]` 输出 `{dryRun,target?,project,
artifacts[],uploaded,verified}`。artifact 为
`{kind,path,sha256?,bytes,url?,wouldWrite,wouldUpload,uploaded}`。
dry-run 不写 `target/dist/`、不创建远端目标、不 PUT、不 GET。

`release --json [target] [--dry-run] [--allow-unsigned]` 输出
`{dryRun,target?,project,jdk,signing:{signed,publicKey?},installerUrl,
bootstrapUrl?,files[],wouldUpload[],uploaded,verified}`。
dry-run 不写 `target/dist/release/`、不签名文件、不创建远端目标、不上传。

`--offline` 对发布链路按 target 判断:无 target 或 `jeno:local:` 允许;
`gh:` 与远端 jeno target 在产生任何网络请求前以 `OFFLINE_FORBIDDEN` 拒绝。
`release` 的 `gh:`/远端 jeno target 同样处理。

`test --json` 使用 workspace/modules 外壳;每个 module 的 `result` 为
`{reportsDir,reportFormat:"junit-xml",tests,passed,failed,skipped,
noTests,reportFiles[],durationMs}`。测试失败仍是有效结果:module exit 1、
根 status=`failed`,并产生 `TEST_FAILED` diagnostic;编译/环境错误无报告时
也为 `TEST_FAILED`。子进程测试日志走 stderr,不得混入 JSON stdout。

`add --repo <name> --json` 输出 discovery 结果：
`{mode:"discover",repository,name,versions[],manifest,lockUpdated:false}`；
它不修改 manifest、不写 lock。索引缺失是 `INDEX_UNAVAILABLE`，索引格式错误是
`INDEX_INVALID`。

`checks[]` 元素为 `{id, status, message, details?}`;`id` 是稳定机器码,
`status` 为 `ok|failed|skipped`。以下字段集合(新增字段向后兼容;删改需新版本)。

**tree**

| 字段 | 类型 |
|---|---|
| `project` | `{name, version}` |
| `budget` | 整数(`max-depth`) |
| `roots[]` | 声明根 id:`[deps]` → `[deps-dev]` → processors,按各自声明顺序 |
| `artifacts[]` | `{id, version, depth, declared, dev, scope, api, local?, children[], apiChildren[]}`;按 id 排序,`children` 排序。`scope` 为 `main`/`dev`/`processor`;`api` 表示显式 `[deps-api]` 闭包;`apiChildren` 是 `children` 中由显式 API 边到达的子集;workspace 本地替换时 `local` 为相对工作区根的路径(否则省略) |

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
| `jeno-repository` | `jeno:` 布局仓库(含 `jeno:local:`;index 缺失时为 `not checked`,有 `index.jeno.toml` 时可比较版本;OSV 坐标仍不可用) |
| `local-repository` | 本地 Maven 布局目录(无版本索引) |
| `named-repository` | 命名 HTTPS Maven 仓库;无法解析的 source 也归到这里 |

### 6.6 publish 与 release 目标

`publish` 发布**库**的三件套(jar/描述符/sha256,§7.1);`release` 发布**应用安装包**
(`bundle` 的 tar.gz + bootstrap)。两者接受同一组目标:

- `gh:owner/repo`
- `jeno:https://host/`
- 私有网络 `jeno:http://<literal-ip>:<port>/…`(字面私有地址,不做 DNS)
- `jeno:local:<dir>`
- `[repos.<name>]` 的仓库名(target 先按声明名解析;解析不到才按 URL 声明解析)

无 target 时只准备本地产物(`target/dist/` 三件套 / `target/dist/release/`),不上传。
非 loopback 的明文 http 目标会在 stderr 打一行“流量与 token 不加密”的 warning;
`JENO_PUBLISH_TOKEN` 默认不会发往 cleartext LAN。named jeno 仓库可在 manifest 写
`insecure = true` 做逐仓库 opt-in;否则只有显式设置 `JENO_ALLOW_INSECURE_TOKEN=1`
这个进程级 escape hatch 才允许。
**Maven 布局不是发布目标**:jeno 不写 POM,`https://host/`、`http://host/`、`local:<dir>` 一律拒绝
(exit 1);其中公网 `http://` 在仓库声明阶段就会被拒绝,私有网络 http 才可使用。
Maven 布局仓库仍可**读取**(`[repos]` 的 `type = "maven"`,§2.1)。

HTTP jeno-layout 发布固定使用 **PUT**,不是 POST:

- 每个 artifact 的最终 URL 由布局唯一确定,PUT 直接创建/替换该资源;
- PUT 是幂等的:重试一次发布不会产生重复资源,也不会让服务端改名;
- 发布完成后必须 `GET` 回读 jar、描述符、sha256 三个文件并逐字节比较;写入成功但回读失败或字节不同 → exit 1;
- POST 适用于服务端分配 URL 或 multipart 表单;采用它需要 host 专属的 endpoint、字段名和响应格式,无法满足“仓库就是 URL”的契约。只支持 POST 的主机需要自行提供 PUT 网关。

### 6.7 全局旗标 `--offline`

**禁止本次运行的一切网络访问。** store 里已有的内容照常使用（会话缓存 → 持久索引 → 字节校验）；缺任何东西即失败，错误消息报出缺失的 URL，并说明如何填充。

不是"优先本地"：没有回退到网络的降级路径。让旗标有时说谎比没有旗标更糟。

每个 store 访问都经过同一个判定点（`ArtifactStore.ensure`），所以 `lock` / `build` / `test` / `info --source` 都自动遵守。

**不因 `--offline` 接受的命令**：`upgrade`(alias `update`)、`outdated`、`audit` 总是联网，传入 `--offline` 报用法错误（退出码 2）。`publish`/`release` 由 target 决定：`jeno:local:` 离线可用；`gh:`、`jeno:https:` 与私有 http 目标在联网前以 `OFFLINE_FORBIDDEN` 拒绝（退出码 2），与实现一致。

**`add` 的 discover 形态**：不带显式版本时（列举 Central 版本、Central 裸名搜索，或 named jeno repository 的 `index.jeno.toml`）是联网操作，`--offline` 下报用法错误（退出码 2）——要离线就带上显式版本：`jeno add "<id>" <version>` 只读 store，`--offline` 下照常可用。

**为什么可行**：store 有持久化的 URL → digest 索引（`store/index/<sha256(url)>.url`）。没有它，索引未命中会是常态（每个 pom 都被当作"没见过"），`--offline` 必然失败。

### 6.7.1 全局旗标 `--locked`

`--locked` 关闭本次运行的 workspace 本地替换：`Substitutions` 返回空集合，所有
classpath、`verify`、`package`、`bundle` 只消费 `jeno.lock` 与 store 中按 sha256
找到的 jar。它不改变 lock 内容，也不拒绝 workspace 的拓扑编排；适合
`jeno --locked verify` / `jeno --locked package` 做发布前检查。`run` 中 target
之后出现的 `--locked` 属于程序参数。

---

## 7. 发行物布局

### 7.1 发布三件套

```
<name>-<version>.jar
<name>-<version>.jeno.toml     # 描述符(§3)
<name>-<version>.sha256       # jar 字节的 sha256
```

生成在 `target/dist/`;`jeno package` 产出 jar,`jeno publish` 写描述符与 sha256。
发布**只发 jeno 布局**:`jeno:https://` 或私有网络 `jeno:http://<literal-ip>` 的
`<base>/<name>/<version>/`,或 `jeno:local:<dir>` 的同构目录;Maven 布局(`group/...`,需要 POM)
不是发布目标(§6.6)。HTTP 上传为 PUT,并以 GET 回读三件套做逐字节校验;目标主机必须是可写、
匿名可读的 PUT 端点。非 loopback 的明文 HTTP 会提示流量与 token 未加密。

### 7.1.1 `index.jeno.toml`（named jeno repository 的版本发现）

布局：

```text
<base>/<name>/index.jeno.toml
```

schema：

```toml
schemaVersion = 1
name = "widget"
versions = ["1.0.0", "1.1.0", "1.2.0"]
```

规则：

- 只存在于 named `jeno:` repository；不存 jar、descriptor 或 sha256
- `name` 必须与路径中的 artifact name 一致
- `versions` 是 jeno-native semver 数组，排序稳定
- `jeno publish` 在 named repository 的三件套上传并回读校验后自动更新
- 更新失败不阻塞 artifact 发布，但会输出 warning，并让 `outdated` 暂时标记为未检查
- index 不进入 `jeno.lock`，不影响 `build`/`lock` 的字节解析
- `jeno add <name> --repo <repo>` 读取它来列版本；没有 index 时仍可用显式 `<repo>:<version>`
- `jeno outdated` 对 lock 中 `source` 为该 named jeno repository 的 row 读取它；
  缺失或损坏时分别降级为 `not checked` / `failures`
- global cross-repository search 不属于本 index；它需要独立 catalog

### 7.2 bundle 形态

```
target/dist/<name>-<version>/
  bin/<name>          # POSIX launcher(注释写明 <name> <version>)
  bin/<name>.cmd      # Windows launcher(精确 jar 名,不用 lib\*)
  lib/<name>-<version>.jar
  lib/toolchain/      # 仅 jeno bundle --toolchain
  install.sh            # JDK 检查 + SHA256SUMS 校验 + staging 交换
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

- `jeno upgrade` 与 Windows `install.ps1` 只校验 sha256,不验 minisign 签名;升级链的信任来自下载源。

**其他**:

- `[repos]` 回退链语法与私库排除(V2)
- ~~`jeno jdk` toolchain 管理~~ —— 2026-11-22 决定不做(见 DESIGN.md §12 Q5):只校验、不管理

---

## 附录 A Maven POM 解码范围

jeno 只把 pom 当作 Central 的**线路格式**读取,不追求 Maven 语义保真;范围如下(理由见 DESIGN §5)。

**支持**:

- parent POM 递归链(版本常藏在 parent)
- 属性插值(`${project.version}` 等)
- `dependencyManagement` 与 BOM `<scope>import</scope>`
- `dependencies` 中 scope = compile / runtime 的条目;`dependencyManagement` 提供的 scope 同样参与决议(依赖自身省略 scope 时生效)
- exclusions、classifiers(按需)、packaging: jar / pom / bundle(按 jar 处理)

**拒绝并报错**:

- version ranges、snapshots
- 非 jar/pom/bundle 的 packaging
- pom 中声明的 `<repositories>`:不跟随(仓库只能由 `jeno.toml` 的 `[repos]` 指定)

**范围**:只保证"把库拖下来且传递依赖正确";scopes 只关心 compile/runtime。

### 7.3 release 布局与 bootstrap

`jeno release` 的输入是 `target/dist/<name>-<version>.tar.gz`(已由 `jeno bundle` 产生),产物在
`target/dist/release/`:`install.sh`、`install.ps1`、`<tar>.sha256`、`release.json`。

`release.json` 至少:`name`、`version`、`artifact`、`sha256`、`jdk`、`jeno`、`platform`(当前 `any`)、
`installers`(`{"unix":"install.sh","windows":"install.ps1"}`)。

**目标布局**(三种目标同构):

```
gh:<owner>/<repo>      https://github.com/<owner>/<repo>/releases/download/<tag>/
                         <name>-<version>.tar.gz | install.sh | install.ps1
                         <tar>.sha256 | release.json
                       稳定入口: .../releases/latest/download/install.sh
                                 .../releases/latest/download/install.ps1
jeno:https://host/      <base>/<name>/<version>/release/
                         <name>-<version>.tar.gz | install.sh | install.ps1
                         <tar>.sha256 | release.json
                       <base>/<name>/<version>/install.sh      (本版本)
                       <base>/<name>/<version>/install.ps1
                       <base>/<name>/install.sh                 (稳定,每次 release 覆盖)
                       <base>/<name>/install.ps1
jeno:local:<dir>        同一目录结构,file: 写入,无网络
```

**签名**:`JENO_MINISIGN_KEY`(secret key,必需除非 `--allow-unsigned`)、`JENO_MINISIGN_PUBKEY`(可选,
缺失时用 `minisign -R` 派生)、`JENO_MINISIGN`(二进制)。tar、`install.sh`、`install.ps1`、`release.json` 各生成
`<file>.minisig`,并上传 `minisign.pub`;`release.json` 增加 `signed`、`minisign.publicKey`、
`signatures`。签名失败 → exit 1,不上传。无签名只能显式 `--allow-unsigned`。

POSIX bootstrap `install.sh` 下载 tar(curl,缺则 wget)→ 校验 sha256(`sha256sum` 或 `shasum -a 256`)→
(签名 release)要求 `minisign`,下载 `<tar>.minisig` 并用内嵌公钥 `minisign -V` 验签,缺失/失败即退出 →
解压到临时目录 → 执行包内 `install.sh`。Windows bootstrap `install.ps1` 用 `Invoke-WebRequest`/`Copy-Item`
下载 tar,`Get-FileHash` 校验 sha256,`tar.exe -xzf` 解压,安装到 `$env:USERPROFILE\.<name>`、
复制 `bin\<name>.cmd`、写 `uninstall.ps1` 并把 bin 加入 User PATH;它不依赖 minisign。unsigned release 只校验 sha256 并打印警告。
bootstrap 必须带内嵌公钥;host 可同时篡改 setup 与公钥,用户需从 README/tag 带外核对 fingerprint。
失败即退出并清理临时目录。URL 写入 bootstrap 时必须是最终可用地址。

**回读校验**:发布后必须从目标读回 tar(`local:` 读文件;`jeno:` GET;`gh:` GET asset,允许短暂重试),
sha256 与 manifest 一致才算成功;失败 exit 1 并报告具体 URL/文件。
