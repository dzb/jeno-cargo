# RELEASE.md — mjm 的发行手册(自举闭环操作面)

设计见 [DESIGN.md §7.5](DESIGN.md)。本文件是操作手册。

## 管线全景

```
开发者                      GitHub Actions                        用户
──────                      ──────────────                        ────
1. 改进 → master ──────→   javac 直接编这棵树
   (只验证,不发版)          用刚编出来的 mjm 跑 lock/build/test
                            ────── 绿了就好,没有 tag、没有 release ──────

2. 想发版时 ───────────→   push tag v* ──────→ 一致性门 → mjm bundle
   ├ 改 mjm.toml 的 version                                              │
   ├ git commit                                                          │
   └ git tag v{version} && git push github v{version}                    ↓
                                                              release + 附件
                                                                        │
                                                                3. mjm update / curl|bash
```

**CI 不装上一个 release 来构建**:上一版与新树同时改语法时(如 0.2.0 的
`<仓库名>:<版本>` 值形态),用它构建会让改动晚一拍生效。javac 引导把"代码在测的
那棵树"和"构建发行物的那棵树"合成一棵。

## 版本模型:人决定,写一处

| 层 | 谁管 | 在哪 |
|---|---|---|
| 整个版本号 | **人工**,发版时改一次 | `mjm.toml` `[project].version` |
| 派生的两份 | `mjm build` 自动生成 | `META-INF/mjm/version.properties`(`--version` 读它)、jar manifest 的 `Implementation-Version` |
| 账本 | git tags | `v{version}`;`mjm update` 比对 GitHub 的 latest release |

`Mj.VERSION` 不是第二个声明,它是那份资源的读取方——CI 的一致性门会同时校验
`tag == mjm.toml 版本 == 刚构建的二进制 --version`,三者不一致即失败。

## 发版步骤

```bash
# 1. 改版本(major.minor 由人决定;新增语法/命令算 minor,纯修复算 patch)
sed -i 's/^version = ".*"$/version = "0.2.0"/' mjm.toml

# 2. 本机验一遍,和 CI 跑同一套
CP="target/classes:$(find vendor -name '*.jar' | tr '\n' ':')"
javac -d target/classes -cp "$(find vendor -name '*.jar' | tr '\n' ':')" $(find src/main/java -name '*.java')
java -cp "$CP" com.jujin.mjm.cli.Mjm lock
java -cp "$CP" com.jujin.mjm.cli.Mjm test

# 3. 提交并推 master(只验证,不发版)
git commit -am "release: v0.2.0" && git push github master

# 4. 打 tag —— 这一步才发版
git tag -a v0.2.0 -m "mjm 0.2.0" && git push github v0.2.0
```

## 次序红线(历史教训)

- tag 一落地,CI 才创建 release;附件随 `gh release create` 一次给出。
  v0.2.0 那次 CI 因附件晚于 tag 数十秒踩过竞态——重发前先删同 tag 的旧 release
  (保留 tag),`ci.yml` 里已经这么做了。
- 发版前必须确认本机二进制读得懂当前树。**0.1.1 读不了 0.2.0 的 manifest**
  (`vendor:2.0.2` 被旧解析器当成版本号),这一次必须用 javac 引导的类,
  而不是装着的旧 mjm。

## 逃生舱(本机二进制读不了当前树时)

```bash
VJARS=$(find vendor -name '*.jar' | tr '\n' ':')
javac -d target/classes -cp "$VJARS" $(find src/main/java -name '*.java')
java -cp "target/classes:$VJARS" com.jujin.mjm.cli.Mjm lock
java -cp "target/classes:$VJARS" com.jujin.mjm.cli.Mjm build
java -cp "target/classes:$VJARS" com.jujin.mjm.cli.Mjm test
java -cp "target/classes:$VJARS" com.jujin.mjm.cli.Mjm bundle
gh release create v{ver} dist/mjm-{ver}.tar.gz --title "mjm v{ver}" --notes "…"
```

修好链条后跑一次 `mjm update`,本机回到链条上。

## 已知边界

- Gitee 仓只镜像代码与 tag,不承载 release(公开发布用 GitHub,已决策)
- `mjm publish` 与本管线无关:它是"把库发布给消费方"的三件套命令;
  mjm 自身的工具发行走 CI 的 bundle + Release,两个语义不混用
- mjm 不提供 `release` 命令:CI 显式调用 `mjm lock` / `build` / `test` / `bundle`,
  再自行 `gh release create`
