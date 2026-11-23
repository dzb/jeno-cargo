# RELEASE.md — jeno 的发行手册(自举闭环操作面)

设计见 [DESIGN.md §7.5](DESIGN.md)。本文件是操作手册。

## 管线全景

```
开发者                      GitHub Actions                        用户
──────                      ──────────────                        ────
1. 改进 → master ──────→   javac 直接编这棵树
   (只验证,不发版)          用刚编出来的 jeno 跑 lock/build/test
                            ────── 绿了就好,没有 tag、没有 release ──────

2. 想发版时 ───────────→   push tag v* ──────→ 一致性门 → jeno bundle --toolchain → minisign 签名
   ├ 改 jeno.toml 的 version                                              │
   ├ git commit                                                          │
   └ git tag v{version} && git push github v{version}                    ↓
                                                              release + 附件
                                                                        │
                                                                3. jeno upgrade / curl|bash
```

**升级地址(唯一事实来源)**:`https://api.github.com/repos/dzb/jeno-cargo/releases/latest` ——
`jeno upgrade`(alias `jeno update`) 查询它,`setup.sh`(动态解析 latest,永不写死版本)与 CI 共用同一约定。
发行物附件命名 `jeno-bin-{version}.tar.gz`(`jeno bundle --toolchain` 产物,与库 jar 三件套区分),
版本号只在 `jeno.toml [project].version` 声明一处。tag 发布同时上传 `jeno-bin-{version}.tar.gz.minisig`
与 `minisign.pub`,并在 CI 里下载回来验签(见下文“发布签名”)。

**CI 不装上一个 release 来构建**:上一版与新树同时改语法时(如 0.2.0 的
`<仓库名>:<版本>` 值形态),用它构建会让改动晚一拍生效。javac 引导把"代码在测的
那棵树"和"构建发行物的那棵树"合成一棵。

## 版本模型:人决定,写一处

| 层 | 谁管 | 在哪 |
|---|---|---|
| 整个版本号 | **人工**,发版时改一次 | `jeno.toml` `[project].version` |
| 派生的两份 | `jeno build` 自动生成 | `META-INF/jeno/version.properties`(`--version` 读它)、jar manifest 的 `Implementation-Version` |
| 账本 | git tags | `v{version}`;`jeno upgrade` 比对 GitHub 的 latest release |

`Jeno.VERSION` 不是第二个声明,它是那份资源的读取方——CI 的一致性门会同时校验
`tag == jeno.toml 版本 == 刚构建的二进制 --version`,三者不一致即失败。

## 发版步骤

```bash
# 1. 改版本(major.minor 由人决定;新增语法/命令算 minor,纯修复算 patch)
sed -i 's/^version = ".*"$/version = "0.2.0"/' jeno.toml

# 2. 本机验一遍,和 CI 跑同一套
CP="target/classes:$(find vendor -name '*.jar' | tr '\n' ':')"
javac -d target/classes -cp "$(find vendor -name '*.jar' | tr '\n' ':')" $(find src/main/java -name '*.java')
java -cp "$CP" com.jujin.jeno.cli.Jeno lock
java -cp "$CP" com.jujin.jeno.cli.Jeno test

# 3. 提交并推 master(只验证,不发版)
git commit -am "release: v0.2.0" && git push github master

# 4. 打 tag —— 这一步才发版
git tag -a v0.2.0 -m "jeno 0.2.0" && git push github v0.2.0
```

## 次序红线(历史教训)

- tag 一落地,CI 才创建 release;附件随 `gh release create` 一次给出。
  v0.2.0 那次 CI 因附件晚于 tag 数十秒踩过竞态——重发前先删同 tag 的旧 release
  (保留 tag),`ci.yml` 里已经这么做了。
- 发版前必须确认本机二进制读得懂当前树。**0.1.1 读不了 0.2.0 的 manifest**
  (`vendor:2.0.2` 被旧解析器当成版本号),这一次必须用 javac 引导的类,
  而不是装着的旧 jeno。

**从旧名迁移**:0.2.21 的旧名版本不能自升级到 jeno;先用
`curl -fsSL https://raw.githubusercontent.com/dzb/jeno-cargo/master/setup.sh | bash`
安装一次,再 `jeno upgrade`。

## 发布签名(minisign)

jeno 自己的发行 tar 在 CI 里签名;用户应用用 `jeno release` 的签名与这条链互不影响。

**密钥**:仓库 secret `JENO_MINISIGN_KEY`,内容是 **unencrypted minisign secret key 的 base64**
(`minisign -G -W` 生成,`base64 -w0` 编码;不要提交、不要打印)。本机备份在
`~/.jeno-signing/jeno.key`(600),公钥 `~/.jeno-signing/jeno.pub`;旧的
`~/.mjm-signing/mjm.key` 可直接搬到新目录后继续使用;当前 key id 为
`569454149E4DE132`。公钥由 CI 用 `minisign -R` 从 secret 派生,不单独存 secret。

**CI 步骤**(`.github/workflows/ci.yml` tag 段):

```bash
# secret 为空 → ::error:: + exit 1;否则:
sudo apt-get update && sudo apt-get install -y minisign
printf '%s' "$MINISIGN_KEY_B64" | base64 -d > "$RUNNER_TEMP/minisign/jeno.key"
chmod 600 "$RUNNER_TEMP/minisign/jeno.key"
minisign -R -s "$RUNNER_TEMP/minisign/jeno.key" -p "$RUNNER_TEMP/minisign/jeno.pub"
minisign -S -q -W -s "$RUNNER_TEMP/minisign/jeno.key" \
  -m dist-release/jeno-bin-$VERSION.tar.gz \
  -x dist-release/jeno-bin-$VERSION.tar.gz.minisig
minisign -V -q -P "$(tail -1 "$RUNNER_TEMP/minisign/jeno.pub")" \
  -m dist-release/jeno-bin-$VERSION.tar.gz \
  -x dist-release/jeno-bin-$VERSION.tar.gz.minisig
cp "$RUNNER_TEMP/minisign/jeno.pub" dist-release/minisign.pub
gh release create "$TAG" …tar.gz …tar.gz.minisig dist-release/minisign.pub …
```

**发布后回读**:CI 用 `gh release download` 拉回 tar、`.minisig`、`minisign.pub`(最多重试 5 次等 CDN),
再用下载的公钥对下载的 tar 验签;失败 exit 1。

**用户手工验签**:

```bash
minisign -V -P "$(tail -1 minisign.pub)" -m jeno-bin-{version}.tar.gz \
  -x jeno-bin-{version}.tar.gz.minisig
```

先确认 `minisign.pub` 的 fingerprint 与本文档/README 的 key id 一致(带外核对),签名只证明
“这个 tar 由该密钥签过”,不证明发布主机本身诚实。

**key 轮换**:生成新 key → 更新 `JENO_MINISIGN_KEY` secret → 下一次 tag 用新 key;旧 release 保留旧
公钥与签名,不追溯重签。key id 变更需同步本文档与 README 的 fingerprint。

## 逃生舱(本机二进制读不了当前树时)

```bash
VJARS=$(find vendor -name '*.jar' | tr '\n' ':')
javac -d target/classes -cp "$VJARS" $(find src/main/java -name '*.java')
java -cp "target/classes:$VJARS" com.jujin.jeno.cli.Jeno lock
java -cp "target/classes:$VJARS" com.jujin.jeno.cli.Jeno build
java -cp "target/classes:$VJARS" com.jujin.jeno.cli.Jeno test
java -cp "target/classes:$VJARS" com.jujin.jeno.cli.Jeno bundle --toolchain
gh release create v{ver} dist/jeno-{ver}.tar.gz --title "jeno v{ver}" --notes "…"
```

修好链条后跑一次 `jeno upgrade`,本机回到链条上。

## 已知边界

- `jeno publish` 与本管线无关:它是"把库发布给消费方"的三件套命令;
  jeno 自身的工具发行走 CI 的 bundle + Release,两个语义不混用
- jeno 不提供 `release` 命令:CI 显式调用 `jeno lock` / `build` / `test` / `bundle`,
  再自行 `gh release create`
