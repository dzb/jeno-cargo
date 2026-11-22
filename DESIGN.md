# mjm — modern java manager

> mjm:Freeway 生态的包管理器与构建工具。
> 不是 Maven 的替代品,是 Freeway 系应用生态的包管理器,**恰好能从 Maven Central 进货**。
> "替代 Maven"是效果,不是目标。

## 发行形态

mjm 的发行不是 uber-jar,是**自足的目录**——maven/gradle/node 的发行形态:

```
mjm-0.1.0/
├── bin/mjm                  ← POSIX sh 启动器(MJM_HOME 推导、JDK 检查、classpath 拼装、exec)
├── lib/
│   ├── mjm.jar              ← mjm 本体(构建产物,非 uber)
│   ├── freeway-commons-2.0.2.jar   ← 运行时闭包(sha 校验过)
│   ├── freeway-ioc-2.0.2.jar
│   ├── slf4j-api-2.0.18.jar
│   └── toolchain/junit/    ← test 生命周期工具链(6 件,离线携带)
└── SHA256SUMS              ← 发行完整性清单
```

**三层依赖模型**(“mjm 是 0 依赖,但构建生命周期需要生态工具”的准确含义):

| 层 | 内容 | 位置 | 获取方式 |
|---|---|---|---|
| mjm 运行时 | JDK 25 + mjm.jar + freeway 闭包 | 发行目录 lib/ | 随发行携带 |
| 生命周期工具链 | junit 平台等 | 发行目录 lib/toolchain/ | 随发行携带(离线可测) |
| 用户工程依赖 | mjm.toml 声明的任何东西 | ~/.mjm/store | 按需下载,sha256 钉定 |

`mjm selfpackage` 由 mjm 自己的 lockfile 组装发行目录(自举闭环:lock → build → selfpackage)。
启动脚本 `bin/mjm` 展开 `lib/*` 与 `lib/toolchain/*/*/` 到 classpath,并要求 JDK 25(构建生命周期编译)。


状态:设计定稿中 · 更新:2026-11-21

---

## 1. 定位与哲学

mjm 的灵魂是"**严格依赖图管理器**",三件套:

1. **严格可见性**——compile classpath 只有直接声明的依赖,根治幽灵依赖
2. **深度预算**——依赖链长度上限可配置(如 2),超限即错,让该死去的库尽快死去
3. **单版本 + MVS**——项目内同库永远只有一个版本,取最大,确定性解析

配套原则:

- **零依赖自举**:mjm 的依赖闭包 = JDK + Freeway(仅此两者)。Freeway 本身零第三方依赖,所以 mjm 整体"有 JDK 就能跑"
- **发布去中心化**:发布 = 上传文件到任意可达 URL(GitHub Releases、内网 Nexus、任意静态服务器)
- **XML 是 Central 的方言,mjm 会听但不会说**:用户可见的世界里 XML 不存在

设计上的对称: mjm 用 Freeway 的 Module 机制约束自己的模块边界,
mjm 又用严格可见性约束用户项目的依赖边界——**这个工具是它自己哲学的第一个践行者**。

---

## 2. 已定决策(ADR 摘要)

| # | 决策 | 理由 |
|---|------|------|
| D1 | manifest 格式 = TOML 子集,自写解析器 | JDK 无 TOML/JSON 解析器;Properties 的 `:` 分隔符会炸掉 Maven 坐标;JSON 无注释。manifest 需要注释与节结构,TOML 是当前事实标准(Cargo/uv/Python) |
| D2 | 不兼容 Maven 的 XML 语法 | Freeway 审美。不迁移 pom 工程、不写 pom、不生成 BOM/parent |
| D3 | pom.xml 作为"线路格式"内部解码 | Central 的元数据就是 pom.xml,拖库绕不开。学 uv 对 PyPI:解析后立刻归一化为 mjm 内部模型,XML 只活在这条管道的第一个方框里 |
| D4 | 冲突策略 = 语义感知 MVS(Go 式 + major 纪律) | 同 major 内取最大;**跨 major = 硬冲突报错**(Go 的 major 纪律翻译进单版本约束,防运行期 NoSuchMethodError)。central 物种 best-effort,非 semver 退回 max-wins。不用 Maven nearest-wins |
| D5 | 深度预算 max-depth,默认硬错误 | 爆预算的错误信息 = 一次"依赖评审时刻",逼向零依赖/小依赖库(与 D-Freeway 哲学闭环) |
| D6 | 严格可见性 = pnpm 精髓 | pnpm 的价值不是"扁平"而是"隔离":compile classpath 只有直接声明依赖,传递依赖只进 runtime classpath |
| D7 | 内容寻址存储 ~/.mjm/store,classpath 直指 store 文件 | 学 pnpm/Coursier:全机一份,免拷贝(Java 没有 node_modules 目录约束,反而更轻) |
| D8 | lockfile 一等公民,sha256 双钉(artifact + 元数据) | release asset 可变,URL 不构成完整性承诺;代码与元数据分别钉死(go.sum 同时哈希 zip 与 go.mod 的先例) |
| D9 | 发布去中心化,GitHub Releases 为一等后端 | Maven 发布链(Nexus/Sonatype/GPG)劝退个人开发者;GitHub 人人都有。owner/repo 即命名空间,无需 group |
| D10 | 内置零依赖测试 runner | node:test 先例:注解 + 反射几百行,不引 JUnit,与零依赖自洽 |
| D11 | 架构 = JDK + Freeway(FreewayApp/SubModule/IoC/EventBus/HttpModule/ScopedCache/Defer) | Maven 用 Plexus 组装自己的先例;硬 dogfood,顺手成为 Freeway 能力的验收工程 |
| D12 | 严格解码,拒绝式解析 | TOML 子集之外/pom 特性子集之外 → 带行号/路径的明确报错,不做猜测式解析 |
| D13 | `[overrides]` 消费端版本否决权,进 V1 | MVS 自动取最大但不知道"有害"(回归/许可证/更高 JDK);retract 是作者端、overrides 是消费端,凑成一对;也是跨 major 硬冲突的合法出口 |
| D14 | TOML 解析器落在 freeway-commons(新 `toml` 包),freeway 配置同步支持 `.toml` | 实测配置层:`ConfigFilePlan.Reader` 按扩展名分派,下游全是扁平 `Map<String,String>`(JSON 走 `Maps.flatten`);TOML 展平规则相同,一个分支接入。commons 已有 `json` 包,`toml` 并列;mjm 仅依赖 Freeway,解析器放 mjm 会倒挂;一次实现四处共用(mjm.toml / 描述符 / mjm.lock / application.toml) |

---

## 3. mjm.toml 语法规范

> 正式文法、字段定义与校验规则见 [SPEC.md](SPEC.md)。

### 3.1 完整示例

```toml
[project]
name = "myapp"
version = "0.1.0"

[java]
version = 21                            # 目标 JDK major(整数,SPEC §2.1)

[deps]                                  # 依赖:值的形态决定物种
"com.example:foo" = "1.2.3"             # 形态A:纯版本 → Maven Central
"mylib" = "gh:user/repo@v1.2.0"         # 形态B:gh: shorthand → GitHub release
"legacy" = "https://cdn.example.com/legacy-0.9.jar"   # 形态C:URL → 裸 jar,叶子

[repos]                                 # 附加 Maven 布局仓库(可选)
corp = "https://nexus.corp.internal/repository/maven/"

[deps.corp]                             # 指定仓库的库,单独一节
"com.corp:internal-lib" = "2.1.0"

[limits]
max-depth = 2                           # 依赖链预算

[overrides]                             # 版本否决权(可选):MVS 之上全图钉定
"io.netty:netty-buffer" = "4.1.100"

[scripts]
dev = "mjm run app.Main"
```

### 3.2 值形态 → 依赖物种

| 值形态 | 物种 | 元数据 | 传递依赖 |
|---|---|---|---|
| `"1.2.3"` | Central | pom.xml(线路格式) | 有 |
| `"corp:1.2.3"` | 命名仓库(Maven 布局) | pom.xml(线路格式) | 有 |
| `"mine:1.2.3"` | 命名仓库(`mjm:` 布局) | 同目录的 `.mjm.toml` 描述符 | 有(描述符里的裸名 = 同仓库兄弟) |
| `"gh:owner/repo@tag"` | mjm 原生 | jar 旁的 `.mjm.toml` 描述符 | 有 |
| `"https://.../x.jar"` | 裸 URL | 无 | 无(叶子) |

- mjm 原生约定:artifact 名 = repo 名,版本 = tag;展开为
  `https://github.com/{owner}/{repo}/releases/download/{tag}/{artifact}-{version}.jar`

- `"corp:1.2.3"` 与 `[deps.corp]` 是同一种语义:只在 `corp` 查找、互不回退;值里的仓库名必须已在
  `[repos]` 声明(否则报错并列出可用仓库名),冒号前不像仓库名的值仍是 central 版本
- 前缀优先:`gh:` / `https://` 永远先匹配(保留前缀);`gh` 也就不能被 `[repos]` 占用(声明即报错)。
  `local` / `http` / `https` / `file` 不是保留名
- `gitee:` 物种已移除(2026-11-22):附件匿名下载需令牌;替代是裸 URL 叶子或自建主机的 `[repos]`
- `[deps.{repo}]` 分区写法保留可读,但已是 legacy(`mjm add` 只产生值形态)
- `mjm:` 仓库:`[repos] mine = "mjm:https://…"`,两层布局 `<base>/<name>/<version>/<name>-<version>.{jar,mjm.toml,sha256}`,
  发布与消费都不需要 `[project].group`;元数据走描述符(与 gh: 同一条解析路径),只支持 https
- 版本一律精确钉定,**不支持 version ranges**
- V2 增强(GOPROXY 先例):`[repos]` 支持回退链与私库排除(排期见 §12"Go 吸收"行,语法届时补进 SPEC)

### 3.3 TOML 子集(自写解析器的实现范围)

支持:

- 节头 `[name]`、`[name.sub]`、表数组 `[[name]]`(lockfile 用)
- 键:裸键或引号字符串(容纳 `group:artifact` 坐标中的冒号与点)
- 值:基础字符串 `"..."`、整数、布尔、字符串数组
- 注释 `#`
- 文件级 UTF-8

子集之外(多行字符串、内联表、点键语法、日期时间类型等)→ **解析错误,带行号**。
一份解析器两处复用:`mjm.toml` 与 `mjm.lock`。

---

## 4. 解析语义

### 4.1 流程

```
mjm.toml
  ↓ 读取 [deps] 全部声明
约束集(物种 × 精确版本)
  ↓ 各物种拉取元数据(见 §5)
overrides 应用:同 id 全图钉定(合法出口)
  ↓
MVS 收敛:同 id 同 major 取最大(跨 major = 硬冲突)→ 全图单版本
  ↓
闭包构建(visited set 防环)
  ↓
深度预算检查(compile/runtime 闭包计入;test-scope = pom 的 test/provided/system scope,解码时即丢弃;[dev-deps] 与 [deps] 同等计入)
  ↓
mjm.lock 写出(每个 artifact:来源、resolved-url、sha256、depth)
```

### 4.2 深度预算

- 定义:直接依赖 = 深度 1,依赖的依赖 = 2,……;`max-depth` 限制路径最大深度
- 在 MVS 收敛后的最终图上取**最长引入路径**(预算的真意是暴露最深的链);**依赖环 = 硬错误**(环让"最深链"无定义)
- compile/runtime 闭包计入预算;test-scope 排除
- 这里的 test-scope 指 **Maven pom 的 `<scope>test/provided/system</scope>`**——`Poms` 解码依赖时就丢弃,它们根本不进图。mjm 自己的 `[dev-deps]` 不是 test-scope:**与 `[deps]` 同等计入预算**(SPEC §2.1 对照表),所以项目要为自己的测试依赖付深度
- 超限 → **硬错误**,错误信息必须包含:
  - 超限依赖的完整路径(谁把谁带进来的)
  - 当前预算值
  - 出口建议:替换零依赖库 / exclude / 调大 max-depth(**无豁免机制,预算是绝对的**——已定,见 §12)
- 发布物的描述符只有事实(自己的 deps),**预算规则只有一份**(消费方的 `max-depth`),发布者不能给依赖链注水
- `mjm why` 可视化每个依赖的深度占用——把深度做成看得见的预算(类比前端 bundle size budget)

### 4.3 MVS 细节(语义感知)

- 同 id 同 major → 取最大版本,结果确定,与声明顺序无关
- **同 id 跨 major(mjm 原生物种)→ 硬冲突报错**,给出完整引入路径;central 物种版本形如 semver 时同样执行,非 semver 形态退回 max-wins
- 描述符 `[retract]` 声明的版本不参与取最大;消费方显式钉定 → 告警但允许
- 遇到 version ranges(`[1.0,2.0)`)→ 报错(拒绝式解析)
- 降级出口 = `[overrides]`(Q2 已定):同 id 全图钉定,覆盖一切版本约束;被钉 id 在 `mjm tree/why` 中强制标注

### 4.4 严格可见性

- **compile classpath = 直接声明的依赖 + 项目自身产物**(传递依赖不进)
- **runtime classpath = MVS 收敛后的完整闭包**
- 后果(有意为之):直接依赖的 API 若暴露了传递依赖的类型,引用该类型会在编译期失败
  → 逼用户显式声明"自己碰过的东西"。这是特性:声明即所见,classpath 即声明
- 实现零成本:mjm 构造 classpath,天然控制可见性

---

## 5. Maven Central 兼容层(线路格式解码)

### 5.1 支持的 pom 特性(内部解码,用户不可见)

- parent POM 递归链解析(版本常藏在 parent 里,绕不开)
- 属性插值(`${project.version}` 等)
- `dependencyManagement` 与 BOM `<scope>import</scope>`(spring-boot-starter 传递 pom 全靠它)
- `dependencies` 中 scope = compile / runtime 的条目
- exclusions、classifiers(按需)、packaging: jar / pom / bundle(按 jar 处理)

### 5.2 拒绝并报错

- version ranges、snapshots(V1 不支持)
- 非 jar/pom/bundle 的 packaging(先报错)
- pom 中声明的 `<repositories>`:**不跟随**(安全决策——pom 不能指路第三方仓库,仓库只由 mjm.toml 的 `[repos]` 控制)

### 5.3 范围声明

只追求"把库拖下来且传递依赖正确",不追求 Maven 语义保真。
scopes 只关心 compile/runtime;provided/test 大部分忽略。
开工前精读 **Coursier**(sbt/scala-cli 的解析器),它已趟平"高速解析 Maven Central"的坑。

---

## 6. 存储与 lockfile

### 6.1 存储

- `~/.mjm/store/` 按 sha256 内容寻址存 jar,全机一份
- **项目锁**:同一项目同时只允许一个写命令。锁文件在 `~/.mjm/locks/<sha256(real path)>.lock`(不在项目里——`clean` 会删 `target/`),同 JVM 内先排 `ReentrantLock` 队列再取 OS 文件锁。持锁命令:`lock`、`build`、`test`、`clean`、`add`、`remove`、`package`、`bundle`、`publish`;只读命令(`run`/`tree`/`why`/`info`)不参与,`info --source` 写的是 `~/.mjm/sources` 而非项目树,也不参与
- URL→digest 索引以**锁里的 url 原样**为 key:相对 `file:` 行因此能跨 checkout 命中,`--offline` 在搬迁后的目录里仍然可用
- classpath 直接指向 store 内文件,**不拷贝、不链接**(Java 无 node_modules 式目录约束)
- store 目录只读(555)+ 下载用文件锁保证并发安全(吸收自 Go module cache:防工具误改 jar)

### 6.2 mjm.lock(正式定义见 [SPEC.md](SPEC.md) §4)

```toml
# 生成产物,提交进版本库,勿手改
[[artifacts]]
id = "com.example:foo"
version = "1.2.3"
source = "central"
url = "https://repo.maven.apache.org/maven2/com/example/foo/1.2.3/foo-1.2.3.jar"
sha256 = "…"
meta-sha256 = "…"
depth = 1

[[artifacts]]
id = "mylib"
version = "1.2.0"
source = "gh:user/repo"
url = "https://github.com/user/repo/releases/download/v1.2.0/mylib-1.2.0.jar"
sha256 = "…"
depth = 1
```

- `local:` 来源的 `url` 写为**相对项目根的** `file:vendor/…` 形式(路径落在项目根之外时才写绝对 `file:///…`),读取时按项目根展开——锁不再绑定某个 checkout 的绝对路径,同一 fixture 在两个目录锁出的字节一致
- sha256 + meta-sha256 双钉(artifact 与元数据),每次下载核对,任一不匹配即失败
- Central artifact 优先用其自带 `.sha256`,缺失则现算并钉死
- lock 存在且与 mjm.toml 一致 → 直接采用,不重新解析

---

## 7. 发布(去中心化)

### 7.1 原则

下载端靠"约定",不靠"服务"。发布 = 把文件传到任意 URL 可达之处。

### 7.2 发布产物(固定三件套)

```
mylib-1.2.0.jar
mylib-1.2.0.mjm.toml     ← 描述符:自己的 deps、java 版本要求(schema 见 Q4)
mylib-1.2.0.sha256
```

发布目标:GitHub Releases(`gh:owner/repo`)、`mjm:` 仓库(`mjm:https://host/`,两层布局、无需 group)、
Maven 布局主机(`https://host/`,legacy,需要 `[project].group`)。

有描述符 = 完整公民(可传递解析);无描述符的裸 jar = 叶子。
描述符支持 `[retract]` 作者端撤回(SPEC §3.4)——与 `[overrides]`(消费端,Q2)凑成一对:坏版本死得快、死得可见。

### 7.3 GitHub 后端(以及为什么不再是 Gitee)

- **GitHub**:release 附件匿名可下载,消费方零配置
- **Gitee**:2026-11-22 随 `gitee:` 物种移除——附件匿名下载需 Bearer 令牌,违背零配置。需要它时用裸 URL 叶子或自建主机
- **自建主机是一等目标**:`mjm publish https://my.host/repo/` 按 Maven 布局(`group/path/artifact/version/`)PUT 三件套——与 `[repos]` 读取的布局完全一致,局域网 http 同样适用。上传协议只有一个(PUT/WebDAV),不为每个后端写适配器
- 迁库只改一行 `[repos]`:从 GitHub 到自建服务器、从公司仓库到镜像,不需要账号迁移或重新发布
- `mjm publish`(V1 ✅):生成 dist/ 三件套 + 上传清单
- `mjm publish gh:owner/repo`(V1.1 代码就绪):原生上传,token 从 `MJM_GITHUB_TOKEN` 环境变量读
- ~~`mjm publish gitee:owner/repo`~~:随 `gitee:` 物种一起移除(2026-11-22);附件需令牌,违反零配置
- 发布端零配置:不需要 Nexus、不需要账号体系,有仓库就能发

### 7.4 安全底线

- sha256 由 lockfile 钉死(§6.2),release asset 可变也不影响字节确定性
- URL 依赖仅允许 https

### 7.5 mjm 自身的发行管线(自举闭环)

mjm 也是自己的狗粮:**代码在测的那棵树,就是构建发行物的那棵树**。**升级地址(唯一事实来源)**:
`https://api.github.com/repos/dzb/mjm-cargo/releases/latest` —— `mjm update` 查询它,
`setup.sh`(动态解析 latest,永不用改)与 CI 共用同一约定。

**发行物**:GitHub Release `v{version}`,附件 `mjm-bin-{version}.tar.gz`(`mjm bundle` 产物,
`-bin` 与库 jar 三件套命名区分)。**版本号只有一处声明**:`mjm.toml [project].version`;
`mjm build` 把它写成 `META-INF/mjm/version.properties`,`--version` 与 `mjm update` 都读那份资源
(目录、jar、安装后的 dist 三种布局都能读到)。`Mj.VERSION` 不是第二个声明,而是那份资源的读取方。

**流水线**(`.github/workflows/ci.yml`;两种触发,两种职责):

```
push master  →  只验证,不发版
  1. javac 直接编 src/main/java → target/classes(不装上一个 release)
  2. 用刚编出来的 mjm 跑 lock → build → test   ← 狗粮常磨

push tag v*  →  验证 + 发行
  3. 一致性门:tag 名 == mjm.toml 版本 == 刚构建的二进制 --version
  4. mjm bundle(用刚构建的 mjm,不是装着的那个)
  5. gh release create v{n} 附件 mjm-bin-{version}.tar.gz
```

**为什么不装上一个 release 来构建**:上一个 release 与新树同时改的时候(改名、锁格式、命令面),
用它构建会让这些改动晚一拍生效——v0.1.4 的发行物还带着旧的九 jar 布局就是这个原因。javac 引导把
"代码在测的那棵"和"构建发行物的那棵"合成一棵。

**版本模型:0.1.x** —— 版本号由人决定,写进 `mjm.toml` 一处;发版动作是打 tag,不是每次 push。
账本是 git tags。

**本地链条**:逐步执行 `mjm lock` → `mjm build` → `mjm test` → `mjm bundle`,再由 CI/人执行 `gh release create`。mjm 不提供 `release` 命令——那一步的编排属于项目自己的 CI,不属于构建工具的职责面。
- 自举链断裂的逃生舱(旧 mjm 读不了新 mjm.toml 等):本机 Maven 引导
  (`mvn -DskipTests package` → `java -cp target/classes:…` 跑 `Mjm`)→ `mjm bundle` → `gh release upload` 手工补发
- 引导链有根:v0.1.0 由 Maven 本地引导(已完成),此后链条不断
- Gitee 仓只镜像代码,release 仅 GitHub(决策:公开发布用 GitHub)

---

## 8. 构建与命令面(草案)

命令面(以 `mjm` 的 usage 输出为准):

| 命令 | 状态 | 说明 |
|---|---|---|
| `mjm add <dep> [ver]` | ✅ | 写入 [deps] 并立即解析;精确版本 + maven-metadata.xml 列版本;裸名走 Central 索引搜索 |
| `mjm lock [--check] [--refresh]` | ✅ | 解析并写出 mjm.lock;`--check` 只在内存里重新生成并与磁盘逐字节比对(Cargo `--locked` 语义,比 build 的"id 在锁里"更严格)、不写任何文件;**需要取元数据**;`--check` 与 `--refresh` 互斥 |
| `mjm tree [--json]` | ✅ | 依赖树 + 深度标注(dev 工件标 `, dev`);`--json` 输出单个 JSON 文档(artifacts 按 id 排序、children 排序,不嵌套) |
| `mjm why <dep>` | ✅ | 谁引入的、占多少深度预算 |
| `mjm build` | ✅ | javac 编译(严格可见性 classpath —— 只含 MAIN 声明);`--release N` 目标化字节码;`-processorpath` 注入处理器;`src/main/resources` 拷入产物 |
| `mjm run <main>` / scripts | ✅ | runtime classpath 运行(排除 dev) |
| `mjm test` | ✅ | fork JVM runner:`@MjmTest` + JUnit Platform;classpath 含 MAIN + dev;`[test]` include/exclude 过滤源 |
| `mjm package` | ✅ | 纯 jar;`--uber` 合并闭包(排除 dev)并输出 `dist/<name>-<ver>-uber.jar`——与普通 jar 不同名,互不覆盖 |
| `mjm bundle` | ✅ | 发行目录 + setup.sh + tar.gz,输出 `dist/`(应用自用;库不需要) |
| `mjm publish` | ✅ | 三件套(dist/);`gh:` 原生上传;**任意 http(s) 主机 → Maven 布局 + HTTP PUT**;描述符只含 MAIN 依赖 |
| `mjm update` / `mjm upgrade` | ✅ | 自升级(alias 同一实现) |
| ~~`mjm release`~~ | **已删除** | 生命周期属于项目自己的 CI;mjm 只提供单步机制 |
| `mjm init [--pom] [--force]` | ✅ | 骨架,或从 pom.xml 迁移(逐条报告不可表达的事实) |
| `mjm remove <id>` | ✅ | 删除一行声明并重锁 |
| `mjm info <id> [--source]` | ✅ | 身份/来源/依赖/源码地址;`--source` 取源码树 |
| `mjm clean [--target] [--dist]` | ✅ | **默认整棵删 `target/`**(无参或 `--target`;Maven 惯例——包括你自己放进 `target/` 的东西;里面只有可重建的 `classes`/`test-classes`,依赖缓存在 `~/.mjm/store` 不在此);`--dist` 才清 `dist/`(交付物,可能含其他版本的产物,所以是显式要求);两个都给就都清;**两个目标先全部探测,任一(含其内部嵌套)是挂载点则整次拒绝、一个字节不删**;**永不触碰 store** |
| `mjm outdated [--json]` | ✅ | 已钉版本 vs 仓库可用版本;只查 Central 与命名 https 仓库,`gh:`/`url`/`local:`/`mjm:` 列为"未检查";预发布默认不报(除非当前就是预发布);跨 major 单独一节;`--json` 输出 `upToDate`/`updates`/`notChecked`/`failures` |
| `--offline` | ✅ | 禁止一切网络访问;store 有则用,缺则报错。`update`/`publish`/`outdated` 不适用 |
| daemon 常驻 | **M6** | 已确认需要:常驻 JVM 热命令,mvnd 已验证可快 2–4 倍 |
| workspaces/monorepo | V2 | 设计模板 = go.work 模式:`mjm.work.toml` 本地覆盖文件,gitignore 不提交 |
| `mjm audit` | V2 | 依赖漏洞扫描,OSV.dev JSON API(commons 解析器现成);govulncheck 先例 |
| `mjm jdk` | V2 | toolchain 管理(Q5 已定方向):下载 Temurin 到 `~/.mjm/jdks`,Go GOTOOLCHAIN 先例 |

编译策略 V1:全量编译,正确优先;增量编译列为后续优化。

---

## 9. 架构(JDK + Freeway)

| mjm 能力 | Freeway 机制 |
|---|---|
| CLI 入口、命令注册 | FreewayApp + SubModule(一个命令一个子模块) |
| 组件装配、实现替换 | IoC 容器 |
| 下载(Central/GitHub) | HttpModule + Defer 并发 |
| 生命周期广播(resolving → downloading → linking → done) | EventBus(进度显示 + 未来 hook 接缝) |
| `~/.mjm` store、解析缓存 | ScopedCache |
| resolver / pom 解码 / store / lockfile / publish | 各自独立 Module,依赖显式声明(ModuleEx 约束) |

IoC 接缝(未来扩展点,不引入插件机制):

- `PublishBackend`: Gh / Http / Manual
- `MetadataSource`: PomMetadataSource / MjDescriptorMetadataSource
- `DownloadClient`

---

## 10. 解析器预算(全部有界)

| 解析对象 | 方案 | 成本 |
|---|---|---|
| mjm.toml / mjm.lock / 描述符 | freeway-commons `toml` 包(D14,SPEC §1 文法) | 一次实现,freeway 配置与 mjm 共用 |
| pom.xml(Central 线路格式) | JDK StAX | 免费 |
| maven-metadata.xml(列版本) | JDK StAX | 免费 |
| Central 搜索 API(返回 JSON) | freeway-commons 自带 JSON 解析器 | 免费(随 Freeway 闭包,Q1 已定) |

---

## 11. 里程碑

> 本节是**历史快照**:每条记录该里程碑完成当天的事实,包括当时的测试数与产物大小。
> 这些数字不随代码演进更新,它们的作用是让人能回溯"那一刻是什么样"。
> 描述**当前**能力时不要引用这里的计数——当前状态看命令的 usage 输出与 CI。

- **M1a** ✅ 完成(2026-11-21):freeway-commons `toml` 包(SPEC §1 文法,40 测试)+ boot 配置级联接入 `.toml`;freeway 全仓 7 模块测试绿
- **M1b** ✅ 完成(2026-11-21):mjm 骨架(groupId `com.jujin8.mjm`,JDK 25,依赖 freeway-commons)+ pom 解码器(`Poms`/`PomXml`/模型,23 测试)——parent 链递归、属性插值 fixpoint(`${revision}` 支持)、DM/BOM 合并、依赖过滤(compile/runtime、非 optional)、子集拒绝(ranges/snapshots/打包类型/坐标不一致/循环父链/DOCTYPE)全部落地
- **M2** ✅ 完成(2026-11-21):解析器全量落地——mjm.toml 封闭 schema(`ProjectReader`,值形态定物种、坐标/https/深度预算规则全校验)、描述符(`DescriptorReader`,facts-only、name/version 一致性、retract 禁自撤)、语义感知 MVS(同 major 取最大、跨 major 硬冲突、overrides 先行、retract 剔除)、exclusions 按路径生效(同库不同排除集各自展开子树)、深度预算(最长路径 + 依赖环 = 硬错误)、`LockFile` 序列化(双钉,digest 走 `DigestSource` 接缝,M3 下载层填充)——63 测试全绿
- **M3** ✅ 完成(2026-11-21):store(`ArtifactStore`——JDK HttpClient 下载、内容寻址 `<sha256>.<ext>`、URL 级文件锁防并发、555 只读、file:// 即本地直取)+ lockfile 回读(`LockFileReader`,封闭 schema、sha64 校验、重复 id 拒绝)+ CLI(`MjModule`/`CommandRunner`/`lock`/`build`/`run`,Freeway IoC 装配、命令为 deferred contributions、ToolProvider 进程内 javac)——**验收达成:mjm 用自己的 mjm.lock 编译并运行了自己**(vendor 自举,36 源文件,80 测试全绿)
- **M4** ✅ 完成(2026-11-21):`mjm publish` V1(生成 dist/ 三件套——jar、描述符、sha256——repo-bound 依赖拒绝发布,目标清单打印 gh:/gitee: 的精确 release URL)'+ host 覆盖(`-Dmjm.github.host`,内网镜像与测试通道,URL 形状不变)+ **gh: 消费链端到端测试**(模拟 GitHub release layout 的 loopback 服务器:描述符→jar→传递描述符→store→lock 双钉);publish 原生上传顺延至 V1.1(需真实凭证验证)
- **M5** ✅ 完成(2026-11-21):内置测试 runner(node:test 形态)+ `mjm tree`(深度可视化)+ `mjm why`(全部引入路径)+ `mjm add`(写入 + 重锁 + 版本列表 + 索引搜索)+ `mjm publish` V1 + **JUnit Platform 双 runner**(@MjmTest 内置 + Jupiter Launcher,现存项目测试不废弃)+ `[build]` 编译参数(`-parameters` 等旗标注入 javac)+ `[scripts]` 接入 `mjm run`(脚本名优先于主类名);Central/GitHub/Gitee host 可覆盖;DigestSource 简化——97 测试全绿
- **M5+** ✅ 完成(2026-11-21):**`mjm package`** —— 内置 uber-jar(零插件哲学延伸):闭包 + 项目 classes 合并为 `target/{name}-{version}.jar`;合并规则 = services 行级并集(ServiceLoader 存活)、签名文件与 module-info 剔除、重复资源首胜;MANIFEST 由 mjm 生成(Main-Class + `mjm-Dependencies` 构建信息,`go version -m` 先例);**自举演示:mjm 给自己打出单文件可执行 jar 并 `java -jar` 运行**(556KB,含全部依赖)
- **M6** daemon(常驻 JVM,热命令;已确认需要,待排期)

---

## 12. Open Questions

### 已解决(2026-11-21)

| # | 决议 |
|---|------|
| Q1 | JSON 解析器用 **freeway-commons 自带**(免自写);`mjm add` 模糊搜索解禁,排期 M5 |
| Q3 | 深度预算**无豁免机制**;出口只有结构性方案:替换库 / exclude / 调大 max-depth |
| Q4 | 文件格式 Spec 独立成文 → [SPEC.md](SPEC.md)(mjm.toml / 描述符 / mjm.lock 的文法、字段、校验、错误规范) |
| Q5 | 分两步走:V1 仅校验(JDK major 不符即报错,编译走 `--release`);V2 `mjm jdk` toolchain 管理(Temurin → `~/.mjm/jdks`,Go GOTOOLCHAIN 先例确认方向) |
| Q6 | daemon 确认要做,列为 **M6** |
| Q7 | token 环境变量定为 `MJM_GITHUB_TOKEN` / `MJM_GITEE_TOKEN` |
| Go 吸收 | 1–3 定稿进 SPEC:跨 major 硬冲突(D4 升级)、mjm.lock 双钉、描述符 [retract];4–7 进排期:[repos] 回退链、mjm jdk、mjm.work.toml 模板、store 只读+文件锁、build-info 嵌入、mjm audit |
| Q2 | `[overrides]` 进 V1:同 id 全图钉定(MVS 之上);URL 物种不适用;跨 major 硬冲突的合法出口;闭包无此 id → 告警;`mjm tree/why` 强制标注。SPEC §2.1 已定稿 |

### 待定

| # | 问题 | 说明 |
|---|------|------|
| Q8 | Gitee 附件 URL 实测 | ✅ **已结论(2026-11-22)**:真机实测发现匿名下载 403(需 Bearer 令牌),于是 `gitee:` 物种**已移除**——携带令牌违背零配置原则。需要 Gitee 构件时用裸 URL 叶子或自建主机 `[repos]` |
| Q9 | 自举期 freeway 依赖来源 | ✅ 已解决(2026-11-21):**`local:` 仓库类型**(本地 Maven 布局目录,`~` 展开与相对路径均支持)+ **vendor 自举**——mjm 的 `vendor/` 目录放 freeway-commons/freeway-ioc/slf4j-api 的 jar 与"独立声明 pom"(无 parent 链,版本重钉去 snapshot 后缀),字节与其他仓库同等 sha256 钉定。snapshot 全局仍然禁止,无需例外 |

---

## 13. 现代包管理器吸收清单(2026-11-21 对标探查)

mjm 的主框架已闭环(自举构建/测试/打包/发布/升级全通)。对标 cargo / uv / npm / pnpm /
Gradle 9 / Maven 4 后,尚未覆盖的场景按下表排期。

> 下表按「缺什么功能」排;按「哪些设计能让 mjm 变少」的横向对比见
> [docs/comparison.md](docs/comparison.md)(2026-11-22,含证据来源与可吸收清单 A1–A7)。
> 两者的结论不冲突:下方是功能缺口,那边是结构简化。

### P0 — 闭环缺口

| # | 缺口 | 对标 | 状态 |
|---|---|---|---|
| 0 | **`mjm source <id>`**(替代 sources jar)+ `mjm publish` 交接指令 | Go tag-archive、pkg.go.dev 可寻址源码;npm 的 `npm pack` 分享 | ✅ 已完成(2026-11-21) |
| 1 | **`[dev-deps]` 依赖分组** | Cargo dev-dependencies、uv PEP 735 dependency-groups | ✅ 已完成(2026-11-21):独立顶层节;解析进同一张锁图,`scope = "dev"`;不进编译可见性/run/package/dist/发布描述符,只进 `mjm test`;MAIN 优先 |
| 2 | `mjm init --pom` / `mjm remove` / `mjm clean` | mvn clean、npm init/uninstall、Maven 迁移 | ✅ 全部完成:init(含 `--pom` 迁移)、remove、clean(2026-11-22,`[--target] [--dist]`,永不碰 store) |
| 3 | ~~发布三件套补 `-sources` jar~~ → **`mjm info <id> [--source]`** | Go/Cargo 从不分离源码与产物;sources jar 是 Java 二进制分发的补丁,不是能力 | ✅ 已完成(2026-11-21):源码**随 tag 走**——gh: 取 tag archive,Central 回退读发布者的 sources jar,URL 叶子明确拒绝。发布侧不产出 sources jar |
| 4 | JUnit XML 测试报告 | mvn surefire 默认产物 | 未做(fork JVM 里产出 `target/test-results/` 即可接 GitHub checks UI) |
| 5 | `mjm outdated` | mvn versions:display-dependency-updates、npm outdated | ✅ 已完成(2026-11-22):`maven-metadata.xml` 列版本;预发布按 qualifier token 判定(不靠 `-` 子串);local:/gh:/url 明确列为未检查 |

### P1 — 现代实践

| # | 缺口 | 对标 |
|---|---|---|
| 6 | 发版前 API 兼容检查 | cargo-semver-checks、japicmp、Revapi(新 jar vs 上一版 jar,release flow 图加一个 verify 节点) |
| 7 | workspace 依赖继承 | Cargo `[workspace.dependencies]`、Gradle version catalogs(freeway 7 模块版本重复声明的现场教训) |
| 8 | `mjm publish --dry-run` | cargo publish --dry-run、npm pack |
| 9 | `--offline` | cargo --offline、pnpm --offline(store 内容寻址,成本近零) |
| 10 | 可复现发行物 | Maven reproducible builds(SOURCE_DATE_EPOCH;dist tar.gz 时间戳归一化) |

### P2 — 记录缓行

- 增量编译/构建缓存(Gradle build cache、cargo incremental)——javac 无增量;M6 daemon 后再议
- 测试并行(Gradle maxParallelForks)——fork JVM 目前串行
- `git:` 任意仓库物种——gh: 与自建主机 `[repos]` 已覆盖主场景
- 注解处理器——`[build].parameters` 传 `-processorpath` 即可,文档化即可
- JPMS module-path——小众,记录不动

### 明确不吸收(品味红线)

- **Maven profiles** —— 配置面泛滥的根源;mjm 用环境外置 + `[scripts]`
- **插件生态** —— 零插件哲学是 mjm 的身份;`[scripts]` 兜底
- **npm 式语义版本范围**(`^1.2.3`)—— 精确钉定 + lockfile 双钉是供应链优势

### 已在既有排期(本次探查确认方向一致)

`mjm audit`(OSV.dev,= cargo audit/npm audit)、`mjm jdk`(= Gradle toolchains / Go GOTOOLCHAIN)、
`mjm.work.toml`(= workspaces)、M6 daemon、`[repos]` 回退链。
