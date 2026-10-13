# 开发环境说明

本页记录 ragsight 开发所需的本地环境，以及 **在作者机器上实测确认过的真实状态**。
所有"现状"结论都来自实际命令输出，不是推测。

---

## 1. 硬性要求

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | **17 及以上** | AgentScope 2.0 要求 17+；domain 层用 17 语法（record / sealed / switch 表达式） |
| Maven | **3.8.0 及以上** | 推荐直接用仓库自带的 `mvnw`，无需本地安装 |
| Git | 任意 | 需要配置 `user.name` / `user.email` |

> 版本门槛由父 `pom.xml` 的 `maven-enforcer-plugin` 强制校验，不满足会**直接构建失败并给出中文提示**，而不是之后报奇怪的编译错误。

---

## 2. 一键验证环境

```bash
git clone git@github.com:qiyec/ragsight.git
cd ragsight

# Windows
mvnw.cmd verify

# Linux / macOS
./mvnw verify
```

**预期结果**：所有模块 `BUILD SUCCESS`，所有测试通过，**不需要任何配置**——不需要 API Key、不需要数据库、不需要 Docker、不需要联网（依赖已在本地仓库的情况下）。

这是本项目的核心设计承诺之一：**默认路径零配置**。任何适配器（真实模型、向量库、PDF 解析）都是 opt-in。

---

## 3. 作者机器实测环境

以下是开发机上的**实测结果**，供对照排查。

### 3.1 JDK

```
JAVA_HOME（未设置，空）
```

PATH 中固定的 JDK：

```
D:\develop\environment\JDK\jdk-17.0.14+7\bin
```

`D:\develop\environment\JDK\` 下并存 4 个版本（**仅 17 在 PATH 中**）：

| 目录 | 实测版本 |
|---|---|
| `jdk8u422-b05` | `1.8.0_422` |
| `jdk-11.0.21` | `11.0.21` |
| `jdk-17.0.14+7` | `17.0.14` ← **PATH 指向此版本** |
| `jdk-21.0.7+6` | `21.0.7` |

**实测确认**：即使 `JAVA_HOME` 未设置，`mvn -v` 仍报告 `Java version: 17.0.14`。
原因是 Maven 的 `mvn.cmd` 在 `JAVA_HOME` 为空时会**回退到 PATH 查找 `java.exe`**：

```bat
if not "%JAVA_HOME%"=="" goto OkJHome
for %%i in (java.exe) do set "JAVACMD=%%~$PATH:i"
```

> ⚠️ **这是隐式依赖**：构建能否成功取决于 PATH 里第一个 `java` 是哪个版本。
> 若将来 PATH 顺序变化，构建会静默切到别的 JDK。
> 因此仓库提供 `mvnw` 包装器，并建议显式设置 `JAVA_HOME`。

### 3.2 Maven

```
Apache Maven 3.8.8
Maven home: D:\develop\environment\Maven\apache-maven-3.8.8
```

#### 镜像配置在全局 settings.xml（容易漏查）

**`~/.m2/settings.xml` 不存在**，但 Maven 已配置阿里云镜像——配置在**安装目录的全局文件**里：

```
D:\develop\environment\Maven\apache-maven-3.8.8\conf\settings.xml
```

已启用（实测日志确认全部依赖走此镜像）：

```xml
<mirror>
  <id>aliyun-central</id>
  <mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/central</url>
</mirror>
```

> ⚠️ **排查提示**：查 Maven 配置时**两个位置都要看**——
> `~/.m2/settings.xml`（用户级）和 `<MAVEN_HOME>/conf/settings.xml`（全局级）。
> 只查前者会得出"没配镜像"的错误结论。
> 因此本项目**不再提供**镜像配置模板，避免重复配置产生冲突。

#### 本地仓库位置（非默认）

全局 settings.xml 把本地仓库改到了：

```xml
<localRepository>D:\develop\environment\Maven\repo</localRepository>
```

因此依赖**不在** `C:\Users\qiye\.m2\repository`。
当前状态：`D:\develop\environment\Maven\repo` 有 53 个顶层 group（更全），
`C:\Users\qiye\.m2\repository` 有 36 个（历史遗留）。

### 3.3 版本管理器现状

作者机器上**没有** SDKMAN、mise、asdf、nvm、volta、jbang。

| 工具 | 状态 | 用途 |
|---|---|---|
| **Scoop** | ✅ 已装（`C:\Users\qiye\scoop`） | 仅装了 `7zip` / `git` / `neofetch`；**未管理 Java 或 Maven**；仅加了 `main` bucket |
| **fnm** | ✅ 已装（v1.39.0） | 管理 Node：`v18.20.8` / `v20.20.2` / `v24.15.0`(default) |
| **uv** | ✅ 已装（v0.11.26） | 管理 Python，如 `cpython-3.13.13` |

> Node 与 Python 工具链**与本项目无关**，此处仅作环境备案。

### 3.4 已知的 PATH 优先级问题

用户 PATH 中相关条目的**先后顺序**：

```
 13. D:\develop\environment\JDK\jdk-17.0.14+7\bin   ← 手工安装，靠前
 17. C:\Users\qiye\scoop\shims                       ← Scoop，被压在后面
 20. D:\develop\environment\Maven\apache-maven-3.8.8\bin
```

**实测结果**：`java` / `javac` 均解析到**手工安装的 17**，Scoop 的 shim 被遮蔽。
`git` 同样解析到 `D:\develop\environment\Git\cmd\git.exe`（非 Scoop 版本）。

> 若将来用 Scoop 安装 JDK，**必须把 `scoop\shims` 提到手工 JDK 之前**，否则 Shims 不生效。

---

## 4. Maven 镜像与 Wrapper

作者机器**已全局配置**阿里云镜像（见 3.2 节），无需额外操作。

仓库自带 **Maven Wrapper**，首次执行 `mvnw` 会自动下载 Maven 3.9.9：

```
.mvn/wrapper/maven-wrapper.properties
  distributionUrl = 阿里云 central / apache-maven-3.9.9-bin.zip
```

> 使用阿里云地址而非官方源，是为了在国内网络下可靠下载。
> 若你的网络访问阿里云不畅，把 `distributionUrl` 改成官方地址即可：
> `https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip`

**关于 `io.agentscope:*`**：这是较新发布的构件。
阿里云 central 镜像对它有同步延迟的可能——引入 AgentScope 依赖时（P5 阶段）
若出现 404，需临时调整 `mirrorOf` 走官方源。届时会在 `PROGRESS.md` 记录。

---

## 5. 可选：按需安装的组件

以下**都不装也能跑通完整的离线验证**。

| 用途 | 需要什么 | 何时需要 |
|---|---|---|
| 真实 Embedding / LLM | 任一 OpenAI 兼容 API Key（DashScope / DeepSeek / SiliconFlow / 本地 Ollama 均可） | 想看 demo 的真实效果时 |
| pgvector 对比实验 | Docker + `pgvector/pgvector:pg16` | v0.1 后期做向量库对比 |
| PDF 解析 | Apache PDFBox（已作为可选依赖声明） | 需要导入 PDF 文档时 |
| Word 解析 | Apache POI（计划中） | 需要导入 .docx 时 |

作者机器上 **Docker 29.5.2 已运行**，D 盘剩余约 338 GB。

---

## 6. 分项目切换 JDK（进阶）

若需要**不同项目用不同 JDK**，推荐 **Maven Toolchains**，它不修改 PATH 或 `JAVA_HOME`：

1. 在 `~/.m2/toolchains.xml` 声明各 JDK 路径
2. 在项目 `pom.xml` 用 `maven-toolchains-plugin` 声明所需版本

Scoop 的 `scoop reset <jdk包>` 只能做**全局切换**，无法按项目隔离，因此不作为本项目方案。

---

## 7. 排查清单

| 症状 | 原因 | 解决 |
|---|---|---|
| `ragsight 需要 JDK 17 及以上` | PATH 里的 `java` 低于 17 | 设置 `JAVA_HOME` 指向 JDK 17+ |
| 编译报 `record` / `sealed` 语法错误 | 用了 JDK 8/11 编译 | 同上 |
| 依赖下载卡住 | 网络异常，或阿里云镜像对某构件未同步 | 检查 `conf/settings.xml` 的镜像配置（第 3.2 节） |
| `scoop install` 的 JDK 不生效 | shims 被 PATH 中靠前的手工 JDK 遮蔽 | 调整 PATH 顺序（第 3.4 节） |
| 测试失败提示找不到 Embedding | 误改了默认适配器 | 默认必须使用 `DeterministicHashEmbedding` |
