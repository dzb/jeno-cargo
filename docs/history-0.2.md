> 写于 mjm 时代，工具现名 jeno。

# 0.2 线小史

状态:**记录**(2026-11-22 整理,2026-11-23 补 0.2.14–0.2.20)
读法:这份文档解释**为什么**,不解释**是什么**。命令与格式的现状见 `README.md` 与 `SPEC.md`;
每一次发布的完整说明在对应的 git tag 注释里(`gh release view vX.Y.Z`),不在这里重复。

起点是 `v0.1.0`(169 个测试、16 个命令、构建带 87 条警告)。终点是 `v0.2.20`
(445 个测试、20 个命令、零警告)。中间 166 个提交,分 22 次发布。

---

## 一、这一线在做什么

一句话:**把「能自举的小工具」变成「可以被别人用的工具」**。功能增量不多(`clean`、`outdated`、
`cache`、`classpath`、`audit`、工作区),大部分提交是在修「说了但没做到」以及「做到了但说错了」。

这个比例不是偶然。mjm 的文档密度很高(SPEC/DESIGN/README 三份,合计上千行),而任何一处
文档跑在代码前面,都会变成用户按着做然后卡住。所以每一轮的模式都是:
**先真跑 → 找到偏差 → 判定谁对 → 改错的那一方**。

---

## 二、时间线

| 版本 | 主题 | 值得记的点 |
|---|---|---|
| **v0.1.1** | 第一轮真跑盘出的十个缺陷 | 36 个提交。`--offline` 对 `add` 不生效、`mjm tree` 不画树、`~/.mj/store` 让用户删错目录、6 处「文档写 `-Dmj.x`、代码读 `-Dmjm.x`」、`[test]` 绕过 schema、descriptor 不吃 `-Dmjm.github.host` |
| **v0.1.1** | **F1:锁跨目录不可复现** | `local:` 仓库写进锁的是绝对 `file://` 路径。改成相对项目根后,两个克隆锁出**逐字节相同**的文件 |
| **v0.2.0** | 值形态 + 原子写 + 保留名 | 依赖可以写 `<仓库名>:<版本>`;`add`/`remove` 先解析后落盘;`[deps.<repo>]` 降为 legacy |
| **v0.2.1** | 零警告 | 87 条警告的根因是 vendor 漏了 `apiguardian`;顺手发现**改 vendor 里的 pom 后 `mjm lock` 看不到**(store 索引把它当不可变远端) |
| **v0.2.2** | 写命令共用一把锁 | `package`/`bundle`/`publish` 纳入,关掉「`clean --dist` 与 `bundle` 并发 → 半个发行包」 |
| **v0.2.3** | `mjm:` 仓库布局 + 删 `gitee:` | 两层布局、不需要 `project.group`;`gitee:` 整族移除(附件需令牌,违背零配置) |
| **v0.2.4** | `update` 重试 | 发版后几分钟内附件取不全 |
| **v0.2.5** | 对比清单 A1–A3、A5、A6 | `dist/` 并进 `target/dist/`(**唯一破坏性变更**)、`cache` 四子命令、XDG 路径、`lock --check`、`--json` |
| **v0.2.6** | JUnit XML | `target/test-results/TEST-*.xml`,surefire 形状 |
| **v0.2.7** | 重试窗口加长 | 从 3 次/1.3 秒到 5 次/14.5 秒 |
| **v0.2.8** | `classpath` + `-o` | IDE 能拿到真正在用的 classpath(严格可见性,不是宽口径) |
| **v0.2.9** | 工作区(前半) | 四个 classpath 装配点收成一个 seam;`init --ws`;模块替换锁里的 jar |
| **v0.2.10** | 工作区(后半) | 根目录按拓扑序跑全部模块,环检测,`--module` |
| **v0.2.11** | 真机发布的三个缺陷 | HTTP/1.1、422 误判、`run` 不读 `[project].main` |
| **v0.2.12** | `audit` + `dependencyManagement` bug | OSV 扫描;一个从第一个提交就在的 scope 缺陷 |
| **v0.2.13** | 文档追上代码 | README 补 `cache`、DESIGN 的 workspaces 从 V2 改 ✅、对比文档标快照、四处小错字 |
| **v0.2.14** | 工作区按模块切根 | `local:../vendor` 按模块自己的根解析,不再按工作区根;同形相对路径不再撞会话缓存 |
| **v0.2.15** | 仓库 = 位置 × 布局 | `mjm:local:`;freeway 的 vendor 从五层目录 + pom 变两层 + 描述符;本地 sibling 按目录判等 |
| **v0.2.16** | 仓库声明类型 + 可复现 jar | `[repos.x] type/url`;锁格式不变;固定 zip 时间戳与顺序后,freeway 重建不再弄脏 7 个锁 |
| **v0.2.17** | 一致性与 release | P0/P1/命名/safety;`upgrade`/`cache purge` 主名;`bundle` 通用化 + `mjm release`;测试 392→410 |
| **v0.2.18** | 兼容矩阵与迁移 | 15 个主流库 Central lock/build 全过;真实 gh release + clean-HOME 安装;init --pom 本地/远端 parent、BOM import、[java] 推导;point-domain 全流程迁移;测试 410→442 |
| **v0.2.19** | 发布签名 | minisign 默认签名 tar/setup/release.json;bootstrap 验签;`--allow-unsigned` 显式逃生;真实 signed local/gh E2E;测试 442→445 |
| **v0.2.20** | mjm 自身发布签名 | CI 用 `MJM_MINISIGN_KEY` 签 `mjm-bin-*.tar.gz`,上传 `.minisig` 与 `minisign.pub`,发布后下载回读验签;`mjm update` 仍只校验完整性 |

---

## 三、几个值得单独讲的故事

### 1. 锁的绝对路径(F1)

最初 `local:vendor` 在锁里写成 `file:///home/…/vendor/…`。同一个工程换个目录 clone,
锁里 9 行 URL 全部失效、`--offline` 直接报缺件,重锁又漂 9 行。

修法不是「让它能找到」,而是**让锁不再记录机器位置**:`file:vendor/…` 相对项目根,读取时展开。
验收标准是「两个不同目录各自锁一次,两份 `mjm.lock` 字节相同」——这条能证伪,所以它成立。

### 2. 两棵树并成一棵(v0.2.5)

曾经 `target/`(中间物)+ `dist/`(交付物)并存。代价是一连串复杂度:`clean` 的两个旗标、
`DistOutput` 的双语义注释、挂载预检要按「请求的树」分支、以及「默认清不清 dist」的争议。

横向对比里 cargo / gradle / maven 的交付物都在**构建树内**。并进 `target/dist/` 之后,
上面四条同时消失。这是这一线唯一一次为「删复杂度」做的破坏性变更。

### 3. 工作区的设计被驳回两次

- **v1** 声称「替换发生在拼 classpath 的那一处」——错的,当时有四个装配点。
- **v2/v3** 加了强制:`package`/`bundle`/`publish` 在替换命中时拒绝,版本必须精确相等。
- **v4** 维护者否掉了这些:「概念要少、要可组合、不设防、打包与发布正交」。
  于是拒绝变成一行 `note`,版本相等变成一行提示,`--allow-version-skew` 这种开关**连同规则一起消失**。

留下的教训写在 `docs/workspace-design.md` §0:没有规则,就不需要放宽规则。

### 4. 真机验证抓到的三个缺陷 + 一个老 bug

用真实 GitHub 做「发布 → 消费」闭环时:

| 缺陷 | 为什么所有单测都没抓到 |
|---|---|
| `gh:` 下载必失败 | JDK 的 HTTP/2 在 release 附件重定向上 EOF;loopback `HttpServer` 只讲 HTTP/1.1,协议协商永远选不到 |
| 422 一律当成「tag 已存在」 | 测试里的 422 永远模拟 tag 冲突;真机上空仓库返回的 422 是「Repository is empty.」 |
| `mjm run` 不读 `[project].main` | 测试都显式给了主类;SPEC 写了、`package`/`bundle` 都读了,只有 `run` 漏了 |

第四个不是真机抓的,是验 `audit` 时顺带发现的:**`dependencyManagement` 的 `test` scope
从未生效**。一条 `log4j-core` 依赖拉进 19 个工件(junit、mockito、assertj…),还被自己的
深度预算拦下。缺陷从第一个提交就在,任何依赖「父 pom 管测试依赖」的库都会中。

---

## 四、仓库模型的重写,与「锁要能当事实」(0.2.14–0.2.16)

freeway 是这段故事的起点:7 个模块,每个模块把 jar 发布到仓库根的 `vendor/`,其余模块再从
那里按坐标消费。workspace(`mjm.ws.toml`,本地文件,不提交)只换编译期的字节,不换坐标;
提交进仓库的锁仍要靠本地 `vendor/` 解析。把它接进来之后,三个问题按顺序暴露。

第一步(v0.2.14)是让编排按模块自己的根解析相对仓库:同一条 `local:../vendor`,在 a 模块和
b 模块必须指向各自的目录,否则两份锁拿到的是同一批字节。下面两节是随后被推翻两次的仓库模型。

### 1. 第一次:位置和布局是两个事实(v0.2.15)

旧的 `[repos]` 只有前缀字符串:

| 写法 | 实际含义 |
|---|---|
| `cascade = "local:../vendor"` | 磁盘上,且是 Maven 布局 |
| `cascade = "mjm:https://host/"` | 网络上,且是两层原生布局 |

问题不在前缀本身,在于它把两个独立的维度绑成了一条链:传输(`local`/`https`)和布局
(Maven/原生)。于是「磁盘上的原生布局」没有拼写。freeway 的 `vendor/` 只能摆成 Maven 的
五层目录并附带 pom——即便这些 jar 全是 mjm 自己刚发布的。

维护者的问题一句话点中:**「vendor 目录是构建的产物么?为什么还是用 maven 的布局?」**
vendor 是构建产物;布局是该仓库的事实,不该由消费工具的默认值决定。

改法不是再加一个前缀分支,而是把两个事实拆开:`Repository` 成为值对象,
`Layout{MAVEN, MJM} × Transport{HTTPS, LOCAL}`,四种合法组合由解析器穷举,
`mjm:local:<dir>` 只是组合出来的 URL 构造器。解析器只有一处:manifest 与锁定文件的
`source` 回读走同一个函数,写方和读方不可能漂移;前缀嗅探被 guard 测试限制在
`Repository.java` 一个文件里。

同一版还修了「本地 sibling」:把模块发布回它自己消费的 `vendor/` 曾被判成「属于另一个
仓库」,因为 https 的规范化基址和本地声明文本在拿两种东西相比。位置相等从此收敛为一个
函数——https 继续现有归一,本地目录取 real path(目标尚不存在时先 real path 父目录再拼回
文件名)。`local:../vendor` 和绝对路径因此是同一个地方。

### 2. 第二次:类型是声明,不是推断(v0.2.16)

第一次重写仍留了一条隐含规则:没有前缀的 `https://` 一律按 Maven 布局。维护者继续追问:
**「"https://" 判断仍不充分,远程用 mjm 发的包也会走进 Maven 布局分支。」**

这句话把问题从「前缀不够多」推到了「前缀根本不是事实」。协议头不携带布局信息;同一个
host 完全可以同时提供两种布局,而 mjm 发布的远程仓库就是 `https://`。靠拼写猜布局,
总会有一种组合没有写法。

于是 `[repos]` 从「名字 → 字符串」变成「名字 → 两个字段」:

```toml
[repos.mine]
type = "mjm"              # 或 "maven"(缺省)
url  = "https://my.host/" # 或一个目录
```

`type` 决定布局,`url` 决定传输;节是封闭的,只允许这两个键,错误带 `file:line:column`。
旧字符串写法继续可解析,并构造出同一个 `Repository`;锁里的 `source` 仍是四种规范声明
之一,所以**锁格式没有变**,新旧写法写出的锁逐字节相同(实测 `cmp` 相同,md5 均为
`65f08856354c3c716f690f8285f4b72d`)。

三次形态放在一起:

| 版本 | 写法 | 布局从哪来 |
|---|---|---|
| ≤0.2.14 | `name = "https://…"`、`"local:…"`、`"mjm:https://…"` | 前缀拼写,四种组合缺 `mjm:local` |
| 0.2.15 | 增加 `"mjm:local:…"` | 前缀拼写,四种组合补齐 |
| 0.2.16 | `[repos.name] type = …` + `url = …` | 显式 `type` 字段 |

两次重写合起来看:第一次把「位置」和「布局」分开,第二次承认「布局」是仓库自己的事实,
既不能从传输协议推断,也不该由前缀拼写决定。旧前缀保留为 legacy,是因为锁里已经写着它们,
不是因为它们还是推荐写法。

### 3. 可复现构建:否则锁记录的是巧合(v0.2.16)

freeway 的 `build.sh` 每次冷启动都重新 `package` 再 `publish` 到 `vendor/`。接上 mjm 后
出现一个怪现象:每跑一次,7 个模块的 `mjm.lock` 全部变脏——提交进仓库的锁,下一次冷启动
就过期。

根因不在锁,在 jar:每个 zip 条目带着写入时刻,条目顺序又跟随 `Files.walk`。同一份源码
两次打包,字节不同,sha256 自然不同;锁只能如实记录「这次碰巧得到的哈希」。

改法是给条目一个唯一入口 `ZipEntries.entry`:DOS 时间固定为 `1980-02-01T00:00:00`
(刚过 DOS 纪元,所有 zip 工具都能表示;用 `setTimeLocal`,不让机器时区参与),creation 和
last-access 也钉到同一时刻——只要这两个字段存在,JDK 就会写带墙钟的 extra field,DOS 时间
固定也挡不住字节漂移。plain 与 uber 都按相对路径排序后写入,压缩级别保持 JDK 默认。

验收是字节级的:连续两次 `package` 的 sha256 相同(plain `6c140078…`,uber `45fbc1fa…`),
`clean --target` 后重建仍相同,条目 DOS 时间全部是 `1980-02-01 00:00:00`,`java -jar` 照常
运行。0.2.16 发版后,用发行版二进制把 freeway 的级联再跑一遍:7 个锁零 diff,`vendor/` 里的
7 个 jar 与各自的 `.sha256` 全部一致(`vendor/` 是本地缓存,不进 git)。

这一条值得单独讲,是因为它决定了「锁是事实」这句话能不能成立。构建产物不可复现时,锁
记录的是某一次构建的偶然结果;可复现之后,同样的输入才真的对应同样的字节,提交进仓库的
锁、离线缓存和发布出去的字节才有同一个锚点。

## 五、被推翻的决定(都留着记录,没有偷偷删掉)

| 决定 | 结局 |
|---|---|
| `[dev-deps]` 不计入深度预算 | **回退**。SPEC §2.1 的对照表写的是「同等计入」;当初那句 `test-scope 不计` 指的是 Maven pom 的 test scope |
| `mjm clean` 默认清 `target/` + `dist/` | 改成默认只清 `target/`(产物不该是「清理构建」的副作用) |
| `package --local` 开关 | **删除**。它唯一的职责是绕过一条已撤销的规则 |
| `--allow-version-skew` 开关 | **删除**。版本的强制取消后,没有需要放宽的东西 |
| `mjm.work.toml` / `[work]` / `--w` | 改成 `mjm.ws.toml` / `[ws]` / `--ws`(`work` 有歧义) |
| `mjm jdk`(下载 Temurin) | **取消**。DESIGN §8 记录:只校验、不管理 |
| `gitee:` 物种 | **删除**。附件匿名下载 403,与零配置冲突 |
| RELEASE.md 里的「push master 自动发版」 | 早就不是事实(`ci.yml` 改成 tag 触发 + javac 引导);文档重写 |
| 仓库布局由前缀或协议头推断 | **两次重写**:先是「位置 × 布局」的值对象(v0.2.15),再改为 `[repos]` 的显式 `type`(v0.2.16);旧前缀保留为 legacy |

---

## 六、留下的账

| 项 | 状态 |
|---|---|
| 挂载检查的 TOCTOU | 已知,概率极低(需要管理员在检查与删除之间挂载) |
| `mjm.ws.toml` 的模块必须仍可解析 | 设计使然:替换只换字节,不换坐标。全新模块要先发布一次 |
| `A4`缓存目录带格式版本号 | 等真要改 store 布局时再做 |
| `publish mjm:`(命名 native 仓库)的真机验证 | 只做过 loopback;需要一台可 PUT 的主机 |
| `mjm audit` 的严重度阈值 / 豁免清单 / SARIF | 刻意没做:概念越少越好,等 CI 真的抱怨 |

---

## 七、教训

这一线留下的十条可复用判断,连同它们各自的事故,记在 [LESSONS.md](../LESSONS.md)。
那份文档不区分版本,只写"当时错在哪、以后怎么判"。
