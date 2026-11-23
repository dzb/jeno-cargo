# jeno — modern java manager

> **DESIGN 是理由,不是契约。** 规范(文件格式、CLI、发行物布局、Maven 解码范围)见
> [SPEC.md](SPEC.md);两者冲突时以 SPEC 为准。本文只回答"为什么这样定、放弃了什么"。

> jeno:Freeway 生态的包管理器与构建工具。
> 不是 Maven 的替代品,是 Freeway 系应用生态的包管理器,**恰好能从 Maven Central 进货**。
> "替代 Maven"是效果,不是目标。

## 发行形态

jeno 的发行不是 uber-jar,是**自足的目录**——maven/gradle/node 的发行形态:

```
jeno-0.1.0/
├── bin/jeno                  ← POSIX sh 启动器(JENO_HOME 推导、JDK 检查、classpath 拼装、exec)
├── lib/
│   ├── jeno.jar              ← jeno 本体(构建产物,非 uber)
│   ├── freeway-commons-2.0.2.jar   ← 运行时闭包(sha 校验过)
│   ├── freeway-ioc-2.0.2.jar
│   ├── slf4j-api-2.0.18.jar
│   └── toolchain/junit/    ← test 生命周期工具链(6 件,离线携带)
└── SHA256SUMS              ← 发行完整性清单
```

**三层依赖模型**(“jeno 是 0 依赖,但构建生命周期需要生态工具”的准确含义):

| 层 | 内容 | 位置 | 获取方式 |
|---|---|---|---|
| jeno 运行时 | JDK 25 + jeno.jar + freeway 闭包 | 发行目录 lib/ | 随发行携带 |
| 生命周期工具链 | junit 平台等 | 发行目录 lib/toolchain/ | 随发行携带(离线可测) |
| 用户工程依赖 | jeno.toml 声明的任何东西 | `jeno/store`(XDG cache) | 按需下载,sha256 钉定 |

`jeno bundle` 由 jeno 自己的 lockfile 组装发行目录(自举闭环:lock → build → bundle;精确布局见
[SPEC.md](SPEC.md) §7)。
启动脚本 `bin/jeno` 展开 `lib/*` 与 `lib/toolchain/*/*/` 到 classpath,并要求 JDK 25(构建生命周期编译)。


状态:决策与理由(2026-11-23 复核) · 规范见 [SPEC.md](SPEC.md)

---

## 1. 定位与哲学

jeno 的灵魂是"**严格依赖图管理器**",三件套:

1. **严格可见性**——compile classpath 只有直接声明的依赖,根治幽灵依赖
2. **深度预算**——依赖链长度上限可配置(如 2),超限即错,让该死去的库尽快死去
3. **单版本 + MVS**——项目内同库永远只有一个版本,取最大,确定性解析

配套原则:

- **零依赖自举**:jeno 的依赖闭包 = JDK + Freeway(仅此两者)。Freeway 本身零第三方依赖,所以 jeno 整体"有 JDK 就能跑"
- **发布去中心化**:发布 = 上传文件到任意可达 URL(GitHub Releases、内网 Nexus、任意静态服务器)
- **XML 是 Central 的方言,jeno 会听但不会说**:用户可见的世界里 XML 不存在

设计上的对称: jeno 用 Freeway 的 Module 机制约束自己的模块边界,
jeno 又用严格可见性约束用户项目的依赖边界——**这个工具是它自己哲学的第一个践行者**。

---

## 2. 已定决策(ADR 摘要)

| # | 决策 | 理由 |
|---|------|------|
| D1 | manifest 格式 = TOML 子集,自写解析器 | JDK 无 TOML/JSON 解析器;Properties 的 `:` 分隔符会炸掉 Maven 坐标;JSON 无注释。manifest 需要注释与节结构,TOML 是当前事实标准(Cargo/uv/Python) |
| D2 | 不兼容 Maven 的 XML 语法 | Freeway 审美。不迁移 pom 工程、不写 pom、不生成 BOM/parent |
| D3 | pom.xml 作为"线路格式"内部解码 | Central 的元数据就是 pom.xml,拖库绕不开。学 uv 对 PyPI:解析后立刻归一化为 jeno 内部模型,XML 只活在这条管道的第一个方框里 |
| D4 | 冲突策略 = 语义感知 MVS(Go 式 + major 纪律) | 同 major 内取最大;**跨 major = 硬冲突报错**(Go 的 major 纪律翻译进单版本约束,防运行期 NoSuchMethodError)。central 物种 best-effort,非 semver 退回 max-wins。不用 Maven nearest-wins |
| D5 | 深度预算 max-depth,默认硬错误 | 爆预算的错误信息 = 一次"依赖评审时刻",逼向零依赖/小依赖库(与 D-Freeway 哲学闭环) |
| D6 | 严格可见性 = pnpm 精髓 | pnpm 的价值不是"扁平"而是"隔离":compile classpath 只有直接声明依赖,传递依赖只进 runtime classpath |
| D7 | 内容寻址存储(XDG cache 下的 jeno/store),classpath 直指 store 文件 | 学 pnpm/Coursier:全机一份,免拷贝(Java 没有 node_modules 目录约束,反而更轻) |
| D8 | lockfile 一等公民,sha256 双钉(artifact + 元数据) | release asset 可变,URL 不构成完整性承诺;代码与元数据分别钉死(go.sum 同时哈希 zip 与 go.mod 的先例) |
| D9 | 发布去中心化,GitHub Releases 为一等后端 | Maven 发布链(Nexus/Sonatype/GPG)劝退个人开发者;GitHub 人人都有。owner/repo 即命名空间,无需 group |
| D10 | 内置零依赖测试 runner | node:test 先例:注解 + 反射几百行,不引 JUnit,与零依赖自洽 |
| D11 | 架构 = JDK + Freeway(FreewayApp/SubModule/IoC/EventBus/HttpModule/ScopedCache/Defer) | Maven 用 Plexus 组装自己的先例;硬 dogfood,顺手成为 Freeway 能力的验收工程 |
| D12 | 严格解码,拒绝式解析 | TOML 子集之外/pom 特性子集之外 → 带行号/路径的明确报错,不做猜测式解析 |
| D13 | `[overrides]` 消费端版本否决权,进 V1 | MVS 自动取最大但不知道"有害"(回归/许可证/更高 JDK);retract 是作者端、overrides 是消费端,凑成一对;也是跨 major 硬冲突的合法出口 |
| D14 | TOML 解析器落在 freeway-commons(新 `toml` 包),freeway 配置同步支持 `.toml` | 实测配置层:`ConfigFilePlan.Reader` 按扩展名分派,下游全是扁平 `Map<String,String>`(JSON 走 `Maps.flatten`);TOML 展平规则相同,一个分支接入。commons 已有 `json` 包,`toml` 并列;jeno 仅依赖 Freeway,解析器放 jeno 会倒挂;一次实现四处共用(jeno.toml / 描述符 / jeno.lock / application.toml) |
| D15 | 发布只写 jeno 布局,Maven 布局只读 | jeno 不生成 POM;发一个自己读不回来的目标等于把"发布"变成"丢文件"。同一台静态主机可以装任一布局,协议由 `[repos]` 的 `type` 显式声明,发布侧只接受能回读的三种目标 |
| D16 | `tree`/`why` 以 `jeno.lock` 为准 | 锁是 `build`/`run`/`package` 实际消费的东西;只解析 manifest 会画出没人构建的图。锁是平表,边由 manifest 补,但 id/version/source/depth/scope 必须逐项一致,否则拒绝出图 |
| D18 | 签名是发布步骤的一部分;jeno 不托管密钥 | sha256 只证明字节完整,不证明来源;minisign 让"推着发布的人"与"构建产物"之间有一条可验证的签名链。密钥属于用户(minisign -G),jeno 只调用本地二进制,不生成、不上传、不打印 secret。「host 被攻破也安全」不成立:公钥与 setup 可被一起替换,需带外核对 fingerprint |
| D17 | `jeno release` 是单步文件分发,不做 CI/生命周期 | 构建、测试、何时发版都属于项目的 CI/flow;工具只负责"把已打好的安装包发出去并读回验证"。它会拒绝隐式 build/test/bundle,否则"发布"就变成"重新构建一个没人审过的产物" |
| D19 | HTTP 发布用 PUT,不用 POST | jeno 布局的最终 URL 是确定的,PUT 把该 URL 直接变成资源:重试幂等、服务端不参与命名、发布后可以逐字节回读。POST 适合由服务端分配 URL 的表单/上传 API,采用它就得为每个 host 定义不同协议,反而更窄。只支持 POST 的主机可用 PUT 网关代理 |
| D20 | 改名 jeno,cargo 风格干净切换 | 旧名文件、旧前缀与旧仓库类型不兼容;双名回退会让“当前文件是否存在”变成隐式规则,锁与描述符来源无法自证。迁移只给命令,不做运行时兼容;0.3.0 是改名后的首个发布 |
| D21 | 测试 toolchain 由 `[toolchain].jars` 显式声明 | 不能从 `org.junit.*` 之类的包名前缀猜:那会把非 JUnit 测试库漏进生产 jar,或把恰好同前缀的库塞进 toolchain。显式 id 清单是 packaging metadata,不改变解析、可见性或锁语义 |
| D22 | 只认 `jeno.toml`/`jeno.lock`/`jeno.ws.toml`;旧名文件只诊断不兼容 | 双名兼容会让“当前文件是否存在”变成隐式回退,锁与 manifest 的来源也更难解释。旧名通过 `jeno doctor` 和 `jeno init --ws` 的迁移提示显式暴露;用户改名/迁移并重新 lock,而不是让两套文件长期共存 |
| D23 | 明文 HTTP 只放行字面私有网络地址 | 局域网自建仓库(192.168/10/172.16)常常没有 TLS,拒绝全部 http 会把“自己机器上的仓库”也排除;但放行主机名或公网 http 等于把安全策略交给 DNS。只接受字面 IP + localhost,不解析域名;非 loopback 的明文目标在 publish/release 打一行“流量与 token 不加密”的 warning。https 行为与“公网 http 拒绝”不变 |

---

## 3. jeno.toml:为什么是 TOML,以及值形态

manifest 需要注释与节结构,而 JDK 自带的 Properties 用 `:` 作分隔符(会炸掉 Maven 坐标),
JSON 又不允许注释——TOML 是当前事实标准(Cargo/uv/Python)。**语法、封闭 schema、字段表与
校验规则全部在 [SPEC.md](SPEC.md) §2**,本文不再复制。

设计上只有一条值得在这里留理由的规则:**值形态决定物种**。`1.2.3` 是 Central,
`corp:1.2.3` 是命名仓库,`gh:owner/repo@tag` 是 release,裸 `https://…jar` 是叶子。
没有第二种声明方式,也没有"默认中央 + 静默回退"——一个值就说明它是什么。

## 4. 解析语义

精确规则(字段、校验、版本排序、深度预算)见 [SPEC.md](SPEC.md) §2.1、§2.3、§2.4;
命令与构建的行为契约见 SPEC §6。这里保留流程与理由。

### 4.1 流程

```
jeno.toml
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
jeno.lock 写出(每个 artifact:来源、resolved-url、sha256、depth)
```

### 4.2 深度预算

- 定义:直接依赖 = 深度 1,依赖的依赖 = 2,……;`max-depth` 限制路径最大深度
- 在 MVS 收敛后的最终图上取**最长引入路径**(预算的真意是暴露最深的链);**依赖环 = 硬错误**(环让"最深链"无定义)
- compile/runtime 闭包计入预算;test-scope 排除
- 这里的 test-scope 指 **Maven pom 的 `<scope>test/provided/system</scope>`**——`Poms` 解码依赖时就丢弃,它们根本不进图。jeno 自己的 `[dev-deps]` 不是 test-scope:**与 `[deps]` 同等计入预算**(SPEC §2.1 对照表),所以项目要为自己的测试依赖付深度
- 超限 → **硬错误**,错误信息必须包含:
  - 超限依赖的完整路径(谁把谁带进来的)
  - 当前预算值
  - 出口建议:替换零依赖库 / exclude / 调大 max-depth(**无豁免机制,预算是绝对的**——已定,见 §12)
- 发布物的描述符只有事实(自己的 deps),**预算规则只有一份**(消费方的 `max-depth`),发布者不能给依赖链注水
- `jeno why` 可视化每个依赖的深度占用——把深度做成看得见的预算(类比前端 bundle size budget)

### 4.3 MVS 细节(语义感知)

- 同 id 同 major → 取最大版本,结果确定,与声明顺序无关
- **同 id 跨 major(jeno 原生物种)→ 硬冲突报错**,给出完整引入路径;central 物种版本形如 semver 时同样执行,非 semver 形态退回 max-wins
- 描述符 `[retract]` 声明的版本不参与取最大;消费方显式钉定 → 告警但允许
- 遇到 version ranges(`[1.0,2.0)`)→ 报错(拒绝式解析)
- 降级出口 = `[overrides]`(Q2 已定):同 id 全图钉定,覆盖一切版本约束;被钉 id 在 `jeno tree/why` 中强制标注

### 4.4 严格可见性

- **compile classpath = 直接声明的依赖 + 项目自身产物**(传递依赖不进)
- **runtime classpath = MVS 收敛后的完整闭包**
- 后果(有意为之):直接依赖的 API 若暴露了传递依赖的类型,引用该类型会在编译期失败
  → 逼用户显式声明"自己碰过的东西"。这是特性:声明即所见,classpath 即声明
- 实现零成本:jeno 构造 classpath,天然控制可见性

---

## 5. Maven Central 兼容层(为什么内部解码 pom)

Central 的元数据是 `pom.xml`;要拖库就绕不开它。jeno 把 XML 当作**线路格式**:解码后立刻归一化为
内部模型,��户可见的世界里 XML 不存在。只追求"把库拖下来且传递依赖正确",不追求 Maven 语义保真;
中央仓库不能通过 pom 指路第三方仓库(pom 里的 `<repositories>` 不跟随)。

支持与拒绝的精确清单在 [SPEC.md](SPEC.md) 附录 A。**为什么不做 POM 生成**见 D15。

## 6. 存储与 lockfile

### 6.1 存储

- store 在 `${XDG_CACHE_HOME:-~/.cache}/jeno/store`、sources 在 `${XDG_CACHE_HOME:-~/.cache}/jeno/sources`、项目锁在 `${XDG_STATE_HOME:-~/.local/state}/jeno/locks`(路径只在 `store/Paths` 一处推导)
- **旧位置兼容**:`~/.jeno/{store,sources,locks}` 里已经存在的目录继续使用(不搬家、不报错,只在 stderr 提一句);新机器/新用户直接用 XDG 路径
- store 按 sha256 内容寻址存 jar,全机一份
- **项目锁**:同一项目同时只允许一个写命令。锁文件在 `${XDG_STATE_HOME:-~/.local/state}/jeno/locks/<sha256(real path)>.lock`(不在项目里——`clean` 会删 `target/`),同 JVM 内先排 `ReentrantLock` 队列再取 OS 文件锁。持锁命令:`lock`、`build`、`test`、`clean`、`add`、`remove`、`package`、`bundle`、`publish`、`release`;只读命令(`run`/`tree`/`why`/`info`)不参与,`info --source` 写的是 sources 目录(XDG cache)而非项目树,也不参与
- URL→digest 索引以**锁里的 url 原样**为 key:相对 `file:` 行因此能跨 checkout 命中,`--offline` 在搬迁后的目录里仍然可用
- classpath 直接指向 store 内文件,**不拷贝、不链接**(Java 无 node_modules 式目录约束)
- store 目录只读(555)+ 下载用文件锁保证并发安全(吸收自 Go module cache:防工具误改 jar)

### 6.2 jeno.lock(正式定义见 [SPEC.md](SPEC.md) §4)

锁是生成物,不是手写配置;结构、字段与不变式只在 SPEC。这里的理由只有三条:

- `local:` 来源的 `url` 写为**相对项目根的** `file:vendor/…` 形式(路径落在项目根之外时才写绝对 `file:///…`),读取时按项目根展开——锁不再绑定某个 checkout 的绝对路径,同一 fixture 在两个目录锁出的字节一致
- sha256 + meta-sha256 双钉(artifact 与元数据),每次下载核对,任一不匹配即失败
- Central artifact 优先用其自带 `.sha256`,缺失则现算并钉死
- lock 存在且与 jeno.toml 一致 → 直接采用,不重新解析;`jeno lock` 本身总是重解,`--check` 只比较——"锁是权威"和"锁总是可重建"必须同时成立

---

## 7. 发布(去中心化)

### 7.1 原则

下载端靠"约定",不靠"服务"。发布 = 把文件传到任意 URL 可达之处。

### 7.2 发布产物(固定三件套)

发布 = 三个文件:`<name>-<version>.{jar,jeno.toml,sha256}`,布局见 [SPEC.md](SPEC.md) §7.1。
理由:**有描述符 = 完整公民**(可传递解析),无描述符的裸 jar = 叶子。描述符带
`[retract]`(作者端撤回,SPEC §3.4),与消费端的 `[overrides]` 凑成一对:坏版本死得快、死得可见。

发布目标只有 jeno 能回读的几种形式(`gh:`、`jeno:https:`、字面私有网络的 `jeno:http:`、
`jeno:local:`);Maven 布局是只读源,不是发布目标——jeno 不写 POM(见 D15/D23)。

### 7.3 GitHub 后端(以及为什么不再是 Gitee)

- **GitHub**:release 附件匿名可下载,消费方零配置
- **Gitee**:2026-11-22 随 `gitee:` 物种移除——附件匿名下载需 Bearer 令牌,违背零配置。需要它时用裸 URL 叶子或自建主机
- **自建主机是一等目标,但只发 jeno 布局**:`jeno publish jeno:https://my.host/`(LAN 可用字面私有 `jeno:http://<ip>/`,会警告明文传输)按两层布局 PUT 三件套,再 GET 回读并逐字节比对;Maven 布局(`https://host/`、`local:<dir>`)**不是发布目标**——jeno 不生成 POM,发出去两边都读不回来。Maven 仓库仍可读(`[repos] type = "maven"`),只是不往它发。用 PUT 而不是 POST,是因为最终 URL 确定、重试幂等;POST 需要 host 专属的上传协议,反而更窄
- **仓内自举是一等目标**:`jeno publish jeno:local:<dir>` 直接把 jeno 布局三件套写进仓库内的目录(`name/version/`,建目录、原子写、不联网);`[repos] x = "jeno:local:<dir>"` 读它。不再需要手写 Maven pom 与 `jar --create` 来喂 `local:` 目录
- 迁库只改一行 `[repos]`:从 GitHub 到自建服务器、从公司仓库到镜像,不需要账号迁移或重新发布
- `jeno publish`:生成 `target/dist/` 三件套;可选目标会上传并回读校验
- `jeno publish gh:owner/repo`(V1.1 代码就绪):原生上传,token 从 `JENO_GITHUB_TOKEN` 环境变量读
- ~~`jeno publish gitee:owner/repo`~~:随 `gitee:` 物种一起移除(2026-11-22);附件需令牌,违反零配置
- 发布端零配置:不需要 Nexus、不需要账号体系,有仓库就能发

### 7.4 安全底线

- sha256 双钉由锁持有,每次下载核对(SPEC §4.2);release asset 可变也不影响字节确定性
- URL 依赖仅允许 https;本地自举走 `local:`/`jeno:local:` 的显式声明,不靠 HTTP 降级
- 仓库的**读**与**发布**是两条不同的信任线:Maven 仓库可以读,发布只写 jeno 布局,避免生成一份
  jeno 自己读不回来的产物

### 7.5 jeno 自身的发行管线(自举闭环)

jeno 也是自己的狗粮:**代码在测的那棵树,就是构建发行物的那棵树**。CI 不装上一个 release 来构建
(新旧树同时改语法时会让改动晚一拍),而是 javac 引导 → 用刚编出的 jeno 跑 lock/build/test →
打 tag 时做一致性门 → `jeno bundle` → 发布。版本号只在 `jeno.toml [project].version` 声明一处,
`jeno build` 把它写进 `META-INF/jeno/version.properties`。

**操作细节(步骤、红线、逃生舱)在 [RELEASE.md](RELEASE.md)**;这里只保留理由。

## 8. 构建与命令面(为什么是这些单步动词)

命令的**契约**(22 个命令、参数、退出码、工作区策略、JSON 字段)在 [SPEC.md](SPEC.md) §6。
这里只留架构理由:

- **单步机制,不做生命周期**:没有自定义 phase,也没有会隐式 build/test/bundle 的 release 生命周期;
  `lock → build → test → package/bundle → publish/release` 各自可单独调用、可被任何 CI 编排。
  `jeno release` 只上传已经 `bundle` 好的安装包。生命周期属于项目自己的 CI,不属于构建工具。
- **严格参数**:未知参数是用法错误;`--` 是 jeno 选项的终点。脚本出错要立刻可见,而不是静默忽略。
- **构建策略 V1 是全量编译**,正确优先;增量编译是后续优化(Gradle build cache 的形态)。

## 9. 架构(JDK + Freeway)

| jeno 能力 | Freeway 机制 |
|---|---|
| CLI 入口、命令注册 | FreewayApp + SubModule(一个命令一个子模块) |
| 组件装配、实现替换 | IoC 容器 |
| 下载(Central/GitHub) | HttpModule + Defer 并发 |
| 生命周期广播(resolving → downloading → linking → done) | EventBus(进度显示 + 未来 hook 接缝) |
| XDG cache 下的 store、解析缓存 | ScopedCache |
| resolver / pom 解码 / store / lockfile / publish | 各自独立 Module,依赖显式声明(ModuleEx 约束) |

IoC 接缝(未来扩展点,不引入插件机制):

- `PublishBackend`: Gh / Http / Manual
- `MetadataSource`: PomMetadataSource / MjDescriptorMetadataSource
- `DownloadClient`

---

## 10. 解析器预算(全部有界)

| 解析对象 | 方案 | 成本 |
|---|---|---|
| jeno.toml / jeno.lock / 描述符 | freeway-commons `toml` 包(D14,SPEC §1 文法) | 一次实现,freeway 配置与 jeno 共用 |
| pom.xml(Central 线路格式) | JDK StAX | 免费 |
| maven-metadata.xml(列版本) | JDK StAX | 免费 |
| Central 搜索 API(返回 JSON) | freeway-commons 自带 JSON 解析器 | 免费(随 Freeway 闭包,Q1 已定) |

---

## 11. 里程碑

> 本节是**历史快照**:每条记录该里程碑完成当天的事实,包括当时的测试数与产物大小。
> 这些数字不随代码演进更新,它们的作用是让人能回溯"那一刻是什么样"。
> 描述**当前**能力时不要引用这里的计数——当前状态看命令的 usage 输出与 CI。
> ※ 已快照:其中部分事实已被后续推翻(`gitee:` 通道已删除、交付物从 `target/{name}-{version}.jar` 迁到 `target/dist/`),当前状态看 SPEC §6 与 `jeno --help`;此处保留完成当天的原记录,不重写历史。

- **M1a** ✅ 完成(2026-11-21):freeway-commons `toml` 包(SPEC §1 文法,40 测试)+ boot 配置级联接入 `.toml`;freeway 全仓 7 模块测试绿
- **M1b** ✅ 完成(2026-11-21):jeno 骨架(groupId `com.jujin8.jeno`,JDK 25,依赖 freeway-commons)+ pom 解码器(`Poms`/`PomXml`/模型,23 测试)——parent 链递归、属性插值 fixpoint(`${revision}` 支持)、DM/BOM 合并、依赖过滤(compile/runtime、非 optional)、子集拒绝(ranges/snapshots/打包类型/坐标不一致/循环父链/DOCTYPE)全部落地
- **M2** ✅ 完成(2026-11-21):解析器全量落地——jeno.toml 封闭 schema(`ProjectReader`,值形态定物种、坐标/https/深度预算规则全校验)、描述符(`DescriptorReader`,facts-only、name/version 一致性、retract 禁自撤)、语义感知 MVS(同 major 取最大、跨 major 硬冲突、overrides 先行、retract 剔除)、exclusions 按路径生效(同库不同排除集各自展开子树)、深度预算(最长路径 + 依赖环 = 硬错误)、`LockFile` 序列化(双钉,digest 走 `DigestSource` 接缝,M3 下载层填充)——63 测试全绿
- **M3** ✅ 完成(2026-11-21):store(`ArtifactStore`——JDK HttpClient 下载、内容寻址 `<sha256>.<ext>`、URL 级文件锁防并发、555 只读、file:// 即本地直取)+ lockfile 回读(`LockFileReader`,封闭 schema、sha64 校验、重复 id 拒绝)+ CLI(`JenoModule`/`CommandRunner`/`lock`/`build`/`run`,Freeway IoC 装配、命令为 deferred contributions、ToolProvider 进程内 javac)——**验收达成:jeno 用自己的 jeno.lock 编译并运行了自己**(vendor 自举,36 源文件,80 测试全绿)
- **M4** ✅ 完成(2026-11-21):`jeno publish` V1(生成 dist/ 三件套——jar、描述符、sha256——repo-bound 依赖拒绝发布,目标清单打印 gh:/gitee: 的精确 release URL)'+ host 覆盖(`-Djeno.github.host`,内网镜像与测试通道,URL 形状不变)+ **gh: 消费链端到端测试**(模拟 GitHub release layout 的 loopback 服务器:描述符→jar→传递描述符→store→lock 双钉);publish 原生上传顺延至 V1.1(需真实凭证验证)
- **M5** ✅ 完成(2026-11-21):内置测试 runner(node:test 形态)+ `jeno tree`(深度可视化)+ `jeno why`(全部引入路径)+ `jeno add`(写入 + 重锁 + 版本列表 + 索引搜索)+ `jeno publish` V1 + **JUnit Platform 双 runner**(@JenoTest 内置 + Jupiter Launcher,现存项目测试不废弃)+ `[build]` 编译参数(`-parameters` 等旗标注入 javac)+ `[scripts]` 接入 `jeno run`(脚本名优先于主类名);Central/GitHub/Gitee host 可覆盖;DigestSource 简化——97 测试全绿
- **M5+** ✅ 完成(2026-11-21):**`jeno package`** —— 内置 uber-jar(零插件哲学延伸):闭包 + 项目 classes 合并为 `target/{name}-{version}.jar`;合并规则 = services 行级并集(ServiceLoader 存活)、签名文件与 module-info 剔除、重复资源首胜;MANIFEST 由 jeno 生成(Main-Class + `jeno-Dependencies` 构建信息,`go version -m` 先例);**自举演示:jeno 给自己打出单文件可执行 jar 并 `java -jar` 运行**(556KB,含全部依赖)
- **M6** daemon(常驻 JVM,热命令;已确认需要,待排期)

---

## 12. Open Questions

### 已解决(压缩;细节见 git 历史)

Q1(JSON 用 freeway-commons)、Q2(`[overrides]` 进 V1)、Q3(深度预算无豁免)、Q4(SPEC 独立成文)、
Q5(JDK 只校验不管理,`jeno jdk` 取消)、Q6(daemon = M6)、Q7(token 环境变量)、Q8(Gitee 实测 403,
物种移除)、Q9(`local:` + vendor 自举)。

### 待定

暂无。

---

## 13. 现代包管理器吸收清单(原则)

详细的功能缺口表、P0/P1/P2 清单与逐项对标已移入
[docs/comparison.md](docs/comparison.md)(历史快照,含证据来源)。这里只留吸收原则:

- **只吸收能让 jeno 变少的东西**:更少的命令、更少的配置面、更少的隐式行为。
- **明确不吸收**:Maven profiles(配置面泛滥)、插件生态(零插件是身份)、npm 式版本范围
  (精确钉定 + 锁双钉是供应链优势)。
- **排期按"缺口"而不是"别人有"**:workspace、audit、可复现发行物都已完成;剩余的
  API 兼容检查、workspace 依赖继承、publish --dry-run 各自是独立行为单。
