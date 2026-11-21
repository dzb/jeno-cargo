# RELEASE.md — mj 的发行手册(自举闭环操作面)

设计见 [DESIGN.md §7.5](DESIGN.md)。本文件是操作手册。

## 管线全景

```
开发者                     GitHub Actions                      用户
──────                     ──────────────                      ────
1. 改进 → master ──────→  装上一个 release
   (无需管版本号)          mj lock/build/test/dist
                           版本账本:tag v0.1.(n+1)
                           release + 附件 + bump commit 回写
                           ────── v0.1.(n+1) 上线 ──────
                                                               2. mj update / curl|bash
```

自举链:**v(n) 构建 v(n+1)**。CI 第一步装的 mj 是"上一个 release",每一版都被上一版
构建出来。链条有根(v0.1.0 由 Maven 本地引导,已成),此后不断。

## 版本模型:0.1.x

| 层 | 谁管 | 在哪 |
|---|---|---|
| major.minor(0.1) | **人工**,想升时改 `mj.toml` 的 `0.1` → `0.2` | mj.toml `[project].version` |
| patch(x) | **CI 自动**,每次 master 有新提交就 +1 | git tags 是账本:`v0.1.*` 的最大 x |

- CI 单一写点:同一条 sed 写 `mj.toml` 与 `Mj.VERSION`,写后 grep 断言兜底
- 没有新提交(如 bump commit 自身被推回)→ CI 判定 SHOULD_RELEASE=false,空跑即过
- `mj update` 只比对"是否等于 latest",CI 恒递增,不存在降级

## 发版两步(常态只有第一步)

### 1. 改进进 master —— 即发版

推上去就发布:CI 装上一个 release → lock/build/test/dist → tag `v0.1.(n+1)` →
release 带附件落地 → bump commit 回写 master(GITHUB_TOKEN 推送不触发新 run,无环)。

### 2. 升 minor(可选,人工)

`mj.toml` 的 `0.1` 改成 `0.2` 即切新账本:没有 `v0.2.*` tag 时下一版就是 `v0.2.0`,
此后 CI 在 `0.2` 段继续递增。

## 次序红线(历史教训)

- release 与 tag 在**同一次 create 调用**落地,附件随 create 一次给出——tag 一落地
  消费端就可能来下载,竞态窗口必须为零(v0.2.0 时 CI 因附件晚于 tag 数十秒而踩过)
- bump commit 用 GITHUB_TOKEN 推:平台防环机制保证它不再触发 run;人工 PAT 推则会循环
- 并发保护:`concurrency: release` 串行化,后跑的 run 看到新账本自然顺延版本

## 逃生舱(自举链断裂时)

症状:上一个 release 无法构建当前 master(如旧 mj 读不了新 mj.toml 语法)。

```bash
# 本机 vendor+javac 引导(与 v0.1.0 同法;Maven 亦可)
VJARS=$(find vendor -name '*.jar' | tr '\n' ':')
javac -d target/classes -cp "$VJARS" $(find src/main/java -name '*.java')
java -cp "target/classes:$VJARS" com.jujin.mj.cli.Mj lock
java -cp "target/classes:$VJARS" com.jujin.mj.cli.Mj build
java -cp "target/classes:$VJARS" com.jujin.mj.cli.Mj test
java -cp "target/classes:$VJARS" com.jujin.mj.cli.Mj dist
gh release create v{ver} target/mj-bin-{ver}.tar.gz --title "mj v{ver}" --notes "…"
```

修好链条后,下一版回归正常自举(跑一次 `mj update` 让本机也回到链条上)。

## 已知边界

- Gitee 仓只镜像代码与 tag,不承载 release(公开发布用 GitHub,已决策)
- `mj publish` 与本管线无关:它是"把库发布给消费方"的三件套命令;
  mj 自身的工具发行走 CI 的 dist + Release,两个语义不混用
- `mj release` 命令(flow 图驱动)在 CI 中的塌缩等 v0.1.2 落地后进行:
  CI 装的"上一个 release"必须已自带该命令
