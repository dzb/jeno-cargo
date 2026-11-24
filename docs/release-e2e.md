# jeno release 真机闭环（Maven Central 之外的第二条证据）

**基线**：jeno `0.2.17` · JDK 25 · 2026-11-23 · 公网 GitHub Release

这是 `jeno release gh:` 的一次端到端记录：demo app → `bundle` → 真实 GitHub Release →
干净 HOME 里 `curl | bash` 安装 → 运行。它不是 CI 门禁；是一次带网络条件的真机快照。

## 目标

```
https://github.com/dzb/jeno-release-demo
```

一个只打印固定字符串的最小 Java app：

```text
release-demo 0.1.0 ok
```

## 发布

```bash
cd /tmp/jeno-release-demo-<ts>
jeno lock && jeno build && jeno bundle
JENO_GITHUB_TOKEN="$(gh auth token)" jeno release gh:dzb/jeno-release-demo
```

结果：exit 0，GitHub Release `v0.1.0`，4 个 asset：

| asset | 大小 |
|---|---:|
| `release-demo-0.1.0.tar.gz` | 2,857 |
| `release-demo-0.1.0.tar.gz.sha256` | 65 |
| `install.sh` | 913 |
| `release.json` | 197 |

`jeno release` 在发完后回读 tar 并校验 sha256；这一轮通过。

## 干净 HOME 安装

目标命令就是 release 打印的那条：

```bash
curl -fsSL https://github.com/dzb/jeno-release-demo/releases/latest/download/install.sh | bash
```

本轮结果：

```text
installing release-demo 0.1.0 to <tmp-home>/.release-demo ...
release-demo 0.1.0 installed
```

运行：

```text
$ <tmp-home>/.release-demo/bin/release-demo
release-demo 0.1.0 ok
```

安装目录含 `bin/`、`lib/release-demo-0.1.0.jar`、`SHA256SUMS`、`uninstall.sh`；`.bashrc`
里写入了 `.release-demo/bin` 的 PATH 行。安装过程没有触碰真实 HOME。

## 本机网络条件（不是 jeno 的缺陷）

这台机器解析 `github.com` 到 `20.247.166.64` 时，443 连接超时；同一个 URL 用
`--resolve github.com:443:140.82.113.3` 可以正常返回 200。为了让 clean-HOME 测试
不被本机 DNS/路由卡住，测试时在 `PATH` 前放了一个临时 `curl` wrapper：

```sh
#!/bin/sh
exec /usr/bin/curl --resolve github.com:443:140.82.113.3 --http1.1 -4 "$@"
```

发布资产和 `install.sh` 都没有被修改；在正常解析 GitHub 的网络里直接跑上面的
`curl | bash` 即可。这个网络现象也解释了为什么 `jeno release` 自身的 Java HTTP/1.1
回读校验能通过，而普通 curl 会卡住。

## 结论

- 真实 `gh:` 发布、4 个 asset、回读校验：通过。
- 干净 HOME 的 `curl | bash` 安装、运行、uninstaller 落盘：通过。
- 未发现 release command 的功能缺口；本机 GitHub 公共下载的路由问题属于网络环境。
- `releases/latest/download/install.sh` 是稳定入口；版本化 `releases/download/v0.1.0/install.sh`
  等价可用，可作为网络排查时的备选。

## Signed releases（minisign，2026-11-23）

本机没有系统 minisign，使用解包的 0.12 二进制：`/tmp/minisign-pkg/root/usr/bin/minisign`。
密钥临时生成在 `/tmp/jeno-sig`（`minisign -G -W`），未写入仓库；安装用干净 HOME。

```text
$ JENO_MINISIGN_KEY=/tmp/jeno-sig/minisign.key   JENO_MINISIGN_PUBKEY=/tmp/jeno-sig/minisign.pub   JENO_MINISIGN=/tmp/minisign-pkg/root/usr/bin/minisign   jeno release jeno:local:/tmp/jeno-sig/repo
released jeno:local:/tmp/jeno-sig/repo; installer:
  curl -fsSL file:///tmp/jeno-sig/repo/sigapp/install.sh | bash
```

产物包含 tar、`install.sh`、`release.json`、三个 `.minisig` 与 `minisign.pub`（版本级与稳定入口各一份）。
`release.json` 的 `signed=true`、`minisign.publicKey`、`signatures` 与实际文件一致。
clean HOME 安装（PATH 含 minisign）成功，launcher 输出 `signed-app-ok`；篡改 `.minisig` 后 bootstrap
在 `minisign -V` 处退出 2，未创建安装目录。

`--allow-unsigned`（无 key）：打印 `warning: releasing unsigned`，bootstrap 只校验 sha256 并再次警告，
安装仍成功。无 key 且未加 flag：exit 1，目标目录未创建。

完整日志：`logs/g246-signed-local.log`、`logs/g246-signed-install.log`。

### Real signed `gh:` release (v0.1.1)

同一 demo repo 又发了一次签名版 `v0.1.1`（8 个 asset）：

```text
minisign.pub
release-demo-0.1.1.tar.gz
release-demo-0.1.1.tar.gz.minisig
release-demo-0.1.1.tar.gz.sha256
release.json
release.json.minisig
install.sh
install.sh.minisig
```

发布时使用 `/tmp/minisign-pkg/root/usr/bin/minisign` 和临时 `-W` 密钥；发布后
`jeno release` 用本地公钥验签。干净 HOME 安装时 `PATH` 同时包含 minisign 和本机
curl DNS 修正 wrapper：

```text
$ curl -fsSL https://github.com/dzb/jeno-release-demo/releases/latest/download/install.sh | bash
installing release-demo 0.1.1 ...
release-demo 0.1.1 installed
$ .release-demo/bin/release-demo
release-demo 0.1.1 ok
```

bootstrap 的 sha256 + minisign 验签都通过后才解压安装。curl wrapper 仅修正本机
`github.com` 路由，不修改发布资产；详见上面的网络条件节。
