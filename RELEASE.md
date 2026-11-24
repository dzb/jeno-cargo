# RELEASE.md — jeno 的发行手册（自举闭环操作面）

设计见 [DESIGN.md §7.5](DESIGN.md)。本文件是操作手册。

## 管线全景

```
开发者                      GitHub Actions                        用户
──────                      ──────────────                        ────
1. 改进 → master ──────→   javac 直接编这棵树
   (只验证,不发版)          用刚编出来的 jeno 跑 lock/build/test
                            ────── 绿了就好,没有 tag、没有 release ──────

2. 想发版时 ───────────→   push tag v* ──────→ 一致性门 → jeno bundle --toolchain
   ├ 改 jeno.toml 的 version                       → tag notes → jeno release gh:dzb/jeno-cargo
   ├ git commit                                    → smoke install from release URL
   └ git tag v{version} && git push github v{version}
```

**升级地址（唯一事实来源）**：`https://api.github.com/repos/dzb/jeno-cargo/releases/latest`。
`jeno upgrade`（alias `jeno update`）查询它；`install.sh` / `install.ps1` 是从最新 release 下载的安装入口。

**资产命名**：`jeno-{version}.tar.gz`（`jeno bundle --toolchain` 产物）及其
`.sha256`、`install.sh`（POSIX bootstrap）、`install.ps1`（Windows bootstrap）、
`release.json`、`minisign.pub` 和对应 `.minisig`。`setup.sh` 不再是资产名。

**CI 不装上一个 release 来构建**：javac 引导把“代码在测的树”和“构建发行物的树”合成一棵；
tag 段用同一份 freshly-built classes 执行 `jeno bundle` 与 `jeno release`。

## 版本模型：人决定，写一处

| 层 | 谁管 | 在哪 |
|---|---|---|
| 整个版本号 | **人工**，发版时改一次 | `jeno.toml` `[project].version` |
| 派生的两份 | `jeno build` 自动生成 | `META-INF/jeno/version.properties`（`--version` 读它）、jar manifest 的 `Implementation-Version` |
| 账本 | git tags | `v{version}`；`jeno upgrade` 比对 GitHub latest release |

`Jeno.VERSION` 不是第二个声明，它是那份资源的读取方——CI 的一致性门会同时校验
`tag == jeno.toml 版本 == 刚构建的二进制 --version`，三者不一致即失败。

## 发版步骤

```bash
# 1. 改版本（major.minor 由人决定；新增语法/命令算 minor，纯修复算 patch）
sed -i 's/^version = ".*"$/version = "0.3.0"/' jeno.toml

# 2. 本机验一遍，和 CI 跑同一套
CP="target/classes:$(find vendor -name '*.jar' | tr '\n' ':')"
javac -d target/classes -cp "$(find vendor -name '*.jar' | tr '\n' ':')" $(find src/main/java -name '*.java')
java -cp "$CP" com.jujin.jeno.cli.Jeno lock
java -cp "$CP" com.jujin.jeno.cli.Jeno test

# 3. 提交并推 master（只验证，不发版）
git commit -am "release: v0.3.0" && git push github master

# 4. 打 annotated tag —— tag message 会成为 release body
git tag -a v0.3.0 -m "jeno 0.3.0" && git push github v0.3.0
```

tag 落地后的 CI 顺序：一致性门 → `jeno bundle --toolchain` → `jeno release
gh:dzb/jeno-cargo --notes-file <tag contents>` → 从 release URL smoke 安装。`jeno release`
负责创建/替换 release 并上传全部资产；不再由手工 `gh release create` 拼装。

## 兼容决定：0.3.0 → 新布局需手动安装一次

0.3.0 及更早的包内安装器叫 `setup.sh`，而新逻辑查找 `install.sh`；不保留 `setup.sh`
垫片。已安装 0.3.0 的用户升级到本发布时需手动安装一次：

```bash
# Linux/macOS
curl -fsSL https://github.com/dzb/jeno-cargo/releases/latest/download/install.sh | bash
# Windows PowerShell
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://github.com/dzb/jeno-cargo/releases/latest/download/install.ps1 | iex"
```

之后 `jeno upgrade` 走新逻辑。

## 发布签名（minisign）

jeno 自己的发行由 `jeno release` 签名；用户应用用同一命令的签名链，互不影响。

**密钥**：仓库 secret `JENO_MINISIGN_KEY`，内容是 **unencrypted minisign secret key 的 base64**
（`minisign -G -W` 生成，`base64 -w0` 编码；不要提交、不要打印）。本机备份在
`~/.jeno-signing/jeno.key`（600），公钥 `~/.jeno-signing/jeno.pub`；旧的
`~/.mjm-signing/mjm.key` 可直接搬到新目录后继续使用；当前 key id 为
`569454149E4DE132`。公钥由 CI 用 `minisign -R` 从 secret 派生，不单独存 secret。

`jeno release` 对 tar、`install.sh`、`install.ps1`、`release.json` 生成 `.minisig`，
并上传 `minisign.pub`。CI 发布后从 release 下载并验签；失败 exit 1。

**用户手工验签**：

```bash
minisign -V -P "$(tail -1 minisign.pub)" -m jeno-{version}.tar.gz \
  -x jeno-{version}.tar.gz.minisig
```

先确认 `minisign.pub` 的 fingerprint 与本文档/README 的 key id 一致（带外核对），签名只证明
“这个 tar 由该密钥签过”，不证明发布主机本身诚实。

**Windows**：PowerShell 安装器没有 minisign 依赖；它校验 tar 的 sha256，信任模型与
`jeno upgrade` 的 sha256 校验一致。

**key 轮换**：生成新 key → 更新 `JENO_MINISIGN_KEY` secret → 下一次 tag 用新 key；旧 release 保留旧
公钥与签名，不追溯重签。key id 变更需同步本文档与 README 的 fingerprint。

## 逃生舱（本机二进制读不了当前树时）

```bash
VJARS=$(find vendor -name '*.jar' | tr '\n' ':')
javac -d target/classes -cp "$VJARS" $(find src/main/java -name '*.java')
java -cp "target/classes:$VJARS" com.jujin.jeno.cli.Jeno lock
java -cp "target/classes:$VJARS" com.jujin.jeno.cli.Jeno build
java -cp "target/classes:$VJARS" com.jujin.jeno.cli.Jeno test
java -cp "target/classes:$VJARS" com.jujin.jeno.cli.Jeno bundle --toolchain
VERSION={ver}
git tag -l --format='%(contents)' "v$VERSION" > notes.md
JENO_GITHUB_TOKEN=... JENO_MINISIGN_KEY="$HOME/.jeno-signing/jeno.key" \
  java -cp "target/classes:$VJARS" com.jujin.jeno.cli.Jeno release \
  gh:dzb/jeno-cargo --notes-file notes.md
```

修好链条后跑一次 `jeno upgrade`，本机回到链条上。

## 已知边界

- `jeno publish` 与本管线无关：它是“把库发布给消费方”的三件套命令；
  jeno 自身的工具发行走 `jeno release`，两个语义不混用。
- `jeno upgrade` 与 Windows 安装器只校验 sha256；不验 minisign（Windows 无 minisign）。
- Windows `install.ps1` 的后续项：安装后清理 `lib` 中不属于本次版本的主 jar；User PATH 的读取、
  去重与卸载改为规范化比较（去尾随 `\`、忽略大小写）。本轮为保持 Windows 真机验证字节一致，暂不改。
- Central 仍是 Maven 通道；GitHub Pages 是 jeno 通道，二者字节可能不同。
