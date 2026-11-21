# RELEASE.md — mj 的发行手册(自举闭环操作面)

设计见 [DESIGN.md §7.5](DESIGN.md)。本文件是操作手册:从提交改进到用户升级,五步。

## 管线全景

```
开发者                          GitHub Actions                    用户
──────                          ──────────────                    ────
1. 改进 → master (CI 全量狗粮)
2. 版本号两处对齐
3. commit + tag v* + push
                                4. 装上一个 release
                                   mj lock/build/test
                                   一致性门(三处版本)
                                   mj dist → gh release create
                                ────── v{ver} 上线 ──────
                                                                  5. mj update / curl|bash
```

自举链:**v(n) 构建 v(n+1)**。CI 第一步装的 mj 是"上一个 release"(本 tag 的
release 此刻还不存在),所以每次发行都是被上一版构建出来的——链条有根
(v0.1.0 由 Maven 本地引导,一次性,已成),此后不断。

## 发版五步

### 1. 改进进 master

日常推送即 CI:每次 push 都由"当前最新 release"跑完整 `mj lock → build → test`。
绿了才有资格发版。

### 2. 版本号两处对齐(必须同步改,或都不改)

| 位置 | 作用 |
|---|---|
| `mj.toml` `[project].version` | 产物命名(`mj-{ver}.tar.gz`、dist 目录名) |
| `src/main/java/com/jujin/mj/cli/Mj.java` `VERSION` | `--version` 输出、`mj update` 比对 |

两处不一致 → CI 一致性门直接红灯,发不出去。这是刻意的:门在流水线上,不靠人记。

### 3. 提交 + 打 tag

```bash
git add mj.toml src/main/java/com/jujin/mj/cli/Mj.java
git commit -m "release: v0.2.0"
git tag v0.2.0
git push github master --tags
git push origin master --tags   # Gitee 只镜像代码,release 仅 GitHub
```

### 4. CI 自动发行(tag 推送即触发)

CI 依次执行:装上一个 release → `mj lock/build/test` → 三处版本一致性门 →
`mj dist` → 附件按 `mj-bin-{ver}.tar.gz` 命名 → `gh release create`。

- **升级地址唯一事实来源**:`https://api.github.com/repos/dzb/mj-cargo/releases/latest`。
  `mj update`、`setup.sh`、CI 共用这一约定,无第二处配置。
- **setup.sh 永不用改**:动态解析 latest(releases/latest 重定向),新版本发完,
  一行安装命令自动指向新版。
- **重发安全**:release 已存在时 CI 先删后建(保留 tag),规避附件 CDN 缓存。

### 5. 用户侧升级(零操作约定)

- 老用户:`mj update` —— 查 latest → 比对 `Mj.VERSION` → 下载 `mj-bin-{ver}.tar.gz`
  → 解包 → 跑包内 `setup.sh` → 下一次 `mj` 调用生效
- 新用户:`curl -fsSL https://raw.githubusercontent.com/dzb/mj-cargo/master/setup.sh | bash`

## 逃生舱(自举链断裂时)

症状:上一个 release 无法构建当前 master(如旧 mj 读不了新 mj.toml 语法)。

```bash
# 本机 Maven 引导(引导链的根,与 v0.1.0 同法)
mvn -B -ntp -DskipTests package
CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" com.jujin.mj.cli.Mj lock
java -cp "$CP" com.jujin.mj.cli.Mj build
java -cp "$CP" com.jujin.mj.cli.Mj test
java -cp "$CP" com.jujin.mj.cli.Mj dist
gh release create v{ver} target/mj-bin-{ver}.tar.gz --title "mj v{ver}" --notes "…"
```

修好链条后,下一版回归正常自举(跑一次 `mj update` 让本机也回到链条上)。

## 已知边界

- Gitee 仓只镜像代码,不承载 release(公开发布用 GitHub,已决策)
- `mj publish` 与本管线无关:它是"把库发布给消费方"的三件套命令;
  mj 自身的工具发行走 `mj dist` + Release,两个语义不混用
- 版本号暂无自动化 bump 工具——两处 sed 脆弱、收益低;一致性门已兜底
