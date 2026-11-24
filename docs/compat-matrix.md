# Maven Central 兼容矩阵（真机证据）

**基线**：jeno `0.3.2` · JDK 25 · Maven Central · `[limits] max-depth = 20` · 2026-11-24
**方法**：每个库一个独立临时项目，先 `jeno lock`，再用一个只引用该库公开类型的
`compat.Main` 跑 `jeno build`。本次使用 `v0.3.2` tag 的临时 worktree，从 tag 源码 bootstrap 出二进制，
并使用全新的 XDG cache/state；不是 CI 门禁，这是某个时点的真机快照。

## 结果

| 库 | 坐标:版本 | lock | build | 备注 |
|---|---|---|---|---|
| Jackson Databind | `com.fasterxml.jackson.core:jackson-databind:2.17.2` | exit 0 · 3 | exit 0 | 干净 |
| Netty Handler | `io.netty:netty-handler:4.1.115.Final` | exit 0 · 7 | exit 0 | 首次用 `io.netty.channel.Channel` 失败：该类在**传递依赖** netty-transport 里，严格可见性不把它放上编译 classpath（设计行为，不是 POM 缺口）。改用本 jar 的 `IdleStateHandler` 通过 |
| Guava | `com.google.guava:guava:33.3.1-jre` | exit 0 · 7 | exit 0 | 干净 |
| OkHttp | `com.squareup.okhttp3:okhttp:4.12.0` | exit 0 · 8 | exit 0 | 干净（Kotlin stdlib 作为传递依赖进入闭包） |
| Kotlin Stdlib | `org.jetbrains.kotlin:kotlin-stdlib:2.0.20` | exit 0 · 2 | exit 0 | 干净 |
| Logback Classic | `ch.qos.logback:logback-classic:1.5.8` | exit 0 · 3 | exit 0 | 干净 |
| Log4j Core | `org.apache.logging.log4j:log4j-core:2.24.0` | exit 0 · 2 | exit 0 | `org.apache.logging.log4j.Logger` 在 log4j-api（传递依赖），严格可见性下不可见；改用 core 自己的 `org.apache.logging.log4j.core.Logger` 通过。**2.14.1 的 `test` 作用域问题已修，这里锁出的是 2 个工件（core+api）** |
| Spring Core | `org.springframework:spring-core:6.1.13` | exit 0 · 2 | exit 0 | 干净 |
| Commons Lang3 | `org.apache.commons:commons-lang3:3.17.0` | exit 0 · 1 | exit 0 | 干净 |
| HikariCP | `com.zaxxer:HikariCP:5.1.0` | exit 0 · 2 | exit 0 | 干净 |
| PostgreSQL JDBC | `org.postgresql:postgresql:42.7.4` | exit 0 · 2 | exit 0 | 干净 |
| SnakeYAML | `org.yaml:snakeyaml:2.3` | exit 0 · 1 | exit 0 | 干净 |
| HttpClient5 | `org.apache.httpcomponents.client5:httpclient5:5.3.1` | exit 0 · 4 | exit 0 | 干净 |
| Micrometer Core | `io.micrometer:micrometer-core:1.13.4` | exit 0 · 5 | exit 0 | 干净 |
| JUnit Jupiter API | `org.junit.jupiter:junit-jupiter-api:5.11.0` | exit 0 · 4 | exit 0 | javac 警告 `Status.STABLE` 缺 apiguardian 类（传递依赖，严格可见性下不在编译 classpath）；编译成功，警告非错误 |

**结论：15/15 锁成功，15/15 编译成功**（修正引用类型之后；v0.3.2 重跑结果与下表一致）。没有发现需要修改 POM 解码器的缺口，因此本单无代码修复。

## 首次失败与判定

| 现象 | 判定 | 处理 |
|---|---|---|
| netty：`程序包 io.netty.channel 不存在` | **设计限制（严格可见性）**：`Channel` 在 netty-transport，不在直接声明的 netty-handler | 改用 `IdleStateHandler`；不修代码 |
| log4j：`程序包 org.apache.logging.log4j 不存在` | 同上：`Logger` 在 log4j-api | 改用 `org.apache.logging.log4j.core.Logger`；不修代码 |
| junit：`需要';'` | 测试 fixture 语法错误（把 `void t(){}` 塞进方法体），不是解码问题 | fixture 改为类型引用后通过 |
| junit：`未知的枚举常量 Status.STABLE` | 严格可见性下传递依赖 apiguardian 不在编译 classpath，javac 警告 | 已知且无害；不修代码 |

## 复现

```bash
mkdir -p /tmp/jeno-compat/jackson/src/main/java/compat
cat > /tmp/jeno-compat/jackson/jeno.toml <<'TOML'
[project]
name = "compat-jackson"
version = "0.1.0"

[java]
version = 17

[limits]
max-depth = 20

[deps]
"com.fasterxml.jackson.core:jackson-databind" = "2.17.2"
TOML
cd /tmp/jeno-compat/jackson
~/.jeno/bin/jeno lock
cat > src/main/java/compat/Main.java <<'JAVA'
package compat;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class Main {
    static void touch() { ObjectMapper m = null; }
}
JAVA
~/.jeno/bin/jeno build
```

其他库把坐标与 import 换掉即可；Netty/Log4j/JUnit 请引用本 jar 自己的类型（见备注），否则第一次会得到严格可见性的
预期结果。
