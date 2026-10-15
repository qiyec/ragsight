# ADR-005：引入 Web 层，以及为什么用 Spring Boot

- **状态**：已接受
- **日期**：2026-10-15
- **决策者**：项目发起人

---

## 背景

项目一直只有命令行形态。这个形态有两个问题：

**一、演示不了。** 报告是运行时动态生成的。任何人（包括面试官）打开
GitHub 仓库，看到的只有源码，看不到"这东西到底能干嘛"。
仓库的第一印象是"一堆 Java 文件"，而不是"一个能用的工具"。

**二、最有价值的信息被埋了。** 一次诊断会跑 9 到 12 次实验，
现在这些数据是一张 Markdown 表。而这个数据天然适合画热力图：

```
              向量    关键词   融合
90字递归      0.646   0.813   0.723
200字递归     0.781   0.938   0.792     ← 颜色深浅一眼看出哪个组合最好
90字固定      0.658   0.795   0.710
```

表格要逐行读，热力图一眼就能看出"切分有多大影响、哪种召回最好"。
**诊断工具的产出是给人看的，可视化不是锦上添花。**

**三、没有交互。** 想知道"把 topK 从 5 改成 10 会怎样"，
现在要重新敲一遍命令。能实时调参数才像工具，而不是像作业。

另外，`AGENTS.md` 里我自己定了规矩：
**"没需求就引入 Spring Boot → 引入要先写 ADR"**。所以这篇 ADR 既是决策记录，
也是守自己定的规矩。

---

## 决策

**新增 `ragsight-web` 模块，用 Spring Boot 3.5.9 提供 Web 服务。**

不改动任何现有模块的职责：

```
ragsight-domain               不动
ragsight-application          不动
ragsight-infrastructure       不动
ragsight-cli                  保留（CI 友好、可脚本化）
ragsight-web                  新增：HTTP 接口 + 单页前端
ragsight-architecture-tests   加规则：application 不许依赖 web
```

**依赖方向仍然是单向的**：`web → infrastructure → application → domain`。

---

## 为什么用 Spring Boot

### 理由一：Web 这件事，Spring Boot 是 Java 生态的默认答案

不用 Spring Boot 的话，选择是：裸 `HttpServer`、Javalin、
Spark Java、Quarkus、Micronaut。逐个看：

| 方案 | 为什么不用 |
|---|---|
| 裸 `java.net.http` / `HttpServer` | 要手写路由、JSON 序列化、静态资源、错误处理。这些和诊断逻辑无关，纯属重新发明轮子 |
| Javalin / Spark | 更轻，但生态小。别人接手时的学习成本反而更高 |
| Quarkus / Micronaut | 为云原生和快速启动优化，代价是新概念（构建期增强、自研 DI）。这个项目不需要那些特性 |
| **Spring Boot** | 几乎所有 Java 开发者都认识，接手上手成本最低。静态资源、JSON、依赖注入全都是现成的 |

### 理由二：第二层（AgentScope）本来就要用它

项目规划的第二层是"用 AgentScope 写一个会自主决策的诊断 Agent"。
AgentScope Java 官方提供的 Starter 全部是 Spring Boot 形态
（`agentscope-spring-boot-starter`、`agentscope-dashscope-spring-boot-starter` 等）。

也就是说，**这个依赖迟早要引**。现在引是为了 Web，
下一步做 Agent 时正好复用，不用再迁移一次。

### 理由三：和项目已有的取舍一致

我在 ADR-002 里禁止 domain 层引第三方库，理由是
"核心算法必须能在没有网络、没有数据库、没有 API Key 的环境下被完整测试"。

引入 Spring Boot **不违反**这条：

- 它只加在 `ragsight-web`，domain 和 application 照旧零依赖
- 架构测试会加一条规则守住"application 不许依赖 web"
- `mvnw verify` 仍然不需要任何外部服务

**这条边界由测试守住，不靠人自觉。**

---

## 被否决的方案

### 方案 A：只在命令行里加一个 HTML 报告生成器

- ✅ 最省事，不改依赖
- ❌ 只解决"报告好看"，不解决"能交互"
- ❌ 演示时仍然是"敲命令 → 打开文件"，不是"打开一个页面就在用"
- 📌 **不废弃**：这个能力本身有价值（能把报告发给别人），
  所以静态 HTML 导出作为 Web 层的一个附带功能保留

### 方案 B：不引 Spring Boot，用裸 HttpServer

- ✅ 零新依赖，符合项目一直以来的克制风格
- ❌ 要实现路由、JSON 编解码、静态资源托管、错误处理、
  参数绑定——**这些都是和"诊断 RAG"毫无关系的代码**，
  却会占掉不少篇幅
- ❌ 第二层做 AgentScope 时还得再引一次 Spring Boot，
  等于同一件事做两遍

### 方案 C：拆成前后端分离（Java 只做 API，前端单独一个项目）

- ❌ 为了一个演示界面维护两套构建、两套依赖管理，不成比例
- ✅ 最终方案里前端仍然是一个独立的单页应用，
  但**直接用 Spring Boot 托管静态资源**，不额外引入 npm 构建链

---

## 影响

### 正面

- 有能演示的形态：打开浏览器就能调参数、看热力图
- 第二层（AgentScope 诊断 Agent）有了调试界面——
  没有可视化的话，调一个自主决策的 Agent 会非常痛苦
- 仓库第一印象从"一堆 Java 文件"变成"一个能用的工具"

### 负面 / 代价

| 代价 | 说明 | 缓解 |
|---|---|---|
| 构建变慢 | Spring Boot 的依赖树不小 | 只在 `ragsight-web` 引入，其他模块不受影响 |
| 依赖变重 | 和项目一直以来的克制风格不同 | 用架构测试守住边界：core 依然零第三方依赖 |
| `mvnw verify` 变慢 | 多了一个模块 | 可接受；CI 有缓存 |
| 前端代码进仓库 | 会引入 JS/HTML/CSS | 不引入 npm 构建链，直接写静态文件 |

### 前端技术选型（同一决策的一部分）

**不引 npm / 打包工具**，直接写原生 HTML + CSS + 少量 JS，
由 Spring Boot 托管静态资源。

理由：界面只有一个页面（参数表单 + 热力图 + 明细表），
不需要组件化、不需要状态管理、不需要构建。
引入 npm 会让"clone 下来跑起来"这条一直守着的规矩变复杂——
现在 clone 后只需要 JDK 和 Maven。

**这条如果将来不成立了**（比如要加很多页面），再写新 ADR 讨论。

---

## 守住的边界

引入 Web 层之后，这些不能变：

1. `ragsight-domain` 零第三方运行时依赖（有架构测试）
2. `ragsight-application` 只依赖 domain（有架构测试）
3. `mvnw verify` 不需要任何外部服务（不连数据库、不起容器）
4. 命令行形态保留且功能不减
5. 诊断结论仍然由固定规则推导，不经过 LLM

第 5 条特别重要：**Web 层只是换了个人机界面，不改变诊断逻辑。**
如果把结论生成挪到 Web 层，就破坏了"结论可复现"这个立身之本。

---

## 相关

- [ADR-001](001-two-layer-architecture.md) —— 两层架构，第二层就是 AgentScope
- [ADR-002](002-no-third-party-in-core.md) —— 为什么 core 不许引第三方库
- `AGENTS.md` —— "引入 Spring Boot 要先写 ADR"这条规矩
