# mjm release 真机闭环（Maven Central 之外的第二条证据）

**基线**：mjm `0.2.17` · JDK 25 · 2026-11-23 · 公网 GitHub Release

这是 `mjm release gh:` 的一次端到端记录：demo app → `bundle` → 真实 GitHub Release →
干净 HOME 里 `curl | bash` 安装 → 运行。它不是 CI 门禁；是一次带网络条件的真机快照。

## 目标

```
https://github.com/dzb/mjm-release-demo
```

一个只打印固定字符串的最小 Java app：

```text
release-demo 0.1.0 ok
```

## 发布

```bash
cd /tmp/mjm-release-demo-<ts>
mjm lock && mjm build && mjm bundle
MJM_GITHUB_TOKEN="$(gh auth token)" mjm release gh:dzb/mjm-release-demo
```

结果：exit 0，GitHub Release `v0.1.0`，4 个 asset：

| asset | 大小 |
|---|---:|
| `release-demo-0.1.0.tar.gz` | 2,857 |
| `release-demo-0.1.0.tar.gz.sha256` | 65 |
| `setup.sh` | 913 |
| `release.json` | 197 |

`mjm release` 在发完后回读 tar 并校验 sha256；这一轮通过。

## 干净 HOME 安装

目标命令就是 release 打印的那条：

```bash
curl -fsSL https://github.com/dzb/mjm-release-demo/releases/latest/download/setup.sh | bash
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

## 本机网络条件（不是 mjm 的缺陷）

这台机器解析 `github.com` 到 `20.247.166.64` 时，443 连接超时；同一个 URL 用
`--resolve github.com:443:140.82.113.3` 可以正常返回 200。为了让 clean-HOME 测试
不被本机 DNS/路由卡住，测试时在 `PATH` 前放了一个临时 `curl` wrapper：

```sh
#!/bin/sh
exec /usr/bin/curl --resolve github.com:443:140.82.113.3 --http1.1 -4 "$@"
```

发布资产和 `setup.sh` 都没有被修改；在正常解析 GitHub 的网络里直接跑上面的
`curl | bash` 即可。这个网络现象也解释了为什么 `mjm release` 自身的 Java HTTP/1.1
回读校验能通过，而普通 curl 会卡住。

## 结论

- 真实 `gh:` 发布、4 个 asset、回读校验：通过。
- 干净 HOME 的 `curl | bash` 安装、运行、uninstaller 落盘：通过。
- 未发现 release command 的功能缺口；本机 GitHub 公共下载的路由问题属于网络环境。
- `releases/latest/download/setup.sh` 是稳定入口；版本化 `releases/download/v0.1.0/setup.sh`
  等价可用，可作为网络排查时的备选。
