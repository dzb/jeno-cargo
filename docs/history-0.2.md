# 0.2 线小史

状态:**记录**(2026-11-22 整理)
读法:这份文档解释**为什么**,不解释**是什么**。命令与格式的现状见 `README.md` 与 `SPEC.md`;
每一次发布的完整说明在对应的 git tag 注释里(`gh release view vX.Y.Z`),不在这里重复。

起点是 `v0.1.0`(169 个测试、16 个命令、构建带 87 条警告)。终点是 `v0.2.13`
(335 个测试、19 个命令、零警告)。中间 109 个提交,分 15 次发布。

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

## 四、被推翻的决定(都留着记录,没有偷偷删掉)

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

---

## 五、留下的账

| 项 | 状态 |
|---|---|
| 挂载检查的 TOCTOU | 已知,概率极低(需要管理员在检查与删除之间挂载) |
| `mjm.ws.toml` 的模块必须仍可解析 | 设计使然:替换只换字节,不换坐标。全新模块要先发布一次 |
| `A4`缓存目录带格式版本号 | 等真要改 store 布局时再做 |
| `publish mjm:`(命名 native 仓库)的真机验证 | 只做过 loopback;需要一台可 PUT 的主机 |
| `mjm audit` 的严重度阈值 / 豁免清单 / SARIF | 刻意没做:概念越少越好,等 CI 真的抱怨 |

---

## 六、教训

这一线留下的十条可复用判断,连同它们各自的事故,记在 [LESSONS.md](../LESSONS.md)。
那份文档不区分版本,只写"当时错在哪、以后怎么判"。
