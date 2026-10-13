# AGENTS.md — 新会话第一读物

> **阅读顺序**：本文件 → `PROGRESS.md`（进度与下一步）。
> 本文件回答"这是什么、边界在哪、有什么规矩"；`PROGRESS.md` 回答"干到哪了、接下来做什么"。

---

## 1. 项目是什么

**ragsight** —— RAG 质量诊断与调优工作台。

解决的核心问题：所有做 RAG 的人都会遇到"效果不好"，但几乎没人能回答**瓶颈在哪一段**。
ragsight 把 RAG 拆成可独立度量的阶段，逐段给出指标，直接定位瓶颈。

**一句话定位**：不是又一个 RAG 框架，而是**诊断工具**。

### 为什么不做"RAG 框架"

AgentScope 2.0 官方已提供 `agentscope-extensions-rag`（Simple / Bailian / Dify / RAGFlow / Haystack 五种集成）。
再做一层 RAG 框架是重复造轮子。ragsight 做的是**官方没有的那层：量化诊断与消融实验**。

---

## 2. 架构：两层，不要混淆

```
┌─────────────────────────────────────────────────────────┐
│ 第二层：Agentic 诊断器（AgentScope）        [P5+ 尚未开始] │
│ 自主做实验的调试助手：ReAct + 工具 + 记忆 + 多 Agent      │
│ 存在理由：全组合实验是组合爆炸（6切分×3检索×2重排=36组）， │
│           必须由 Agent 看结果决定下一步试什么             │
└───────────────────────┬─────────────────────────────────┘
                        │ 作为 Tool 调用
┌───────────────────────▼─────────────────────────────────┐
│ 第一层：诊断引擎（纯 Java，六边形架构）      [P1-P4 当前]  │
│ 确定性的度量仪：每个实验都是纯函数，结果必须可复现        │
└─────────────────────────────────────────────────────────┘
```

**关键原则**：第一层**绝不允许**出现 LLM 调用或随机性。
诊断结论必须可复现——同样的输入跑两次必须得到同样的数字。
一旦指标计算依赖 LLM，工具本身就不可信了。

---

## 3. 模块结构与依赖规则

```
ragsight-parent
├── ragsight-domain          纯 Java，零第三方运行时依赖
├── ragsight-application     只依赖 domain
├── ragsight-infrastructure  实现 domain 的端口（适配器）
└── ragsight-cli             装配一切，picocli 入口
```

### 铁律（违反即架构失败）

| # | 规则 | 原因 |
|---|---|---|
| **R1** | `ragsight-domain` **不允许任何第三方运行时依赖** | 领域逻辑必须能在无网络、无数据库、无 API Key 下完整单测 |
| **R2** | `ragsight-application` 只能依赖 `domain` | 用例编排不该知道具体向量库/模型 |
| **R3** | 外部能力一律以**接口（端口）**表达在 domain 中，实现在 infrastructure | 依赖倒置 |
| **R4** | 默认路径必须**零配置**可用 | clone 后 `mvn verify` 必须全绿，无需任何环境准备 |
| **R5** | 诊断结果必须**可复现** | 确定性算法，禁止在 domain 层引入随机/LLM |

> R1 将由 `ragsight-architecture-tests` 模块用 ArchUnit 自动校验（P1 引入）。
> 在那之前，靠人工遵守 + code review。

---

## 4. 包结构

```
cn.qiyec.ragsight.domain
├── knowledge/          限界上下文①：知识库（文档的生命周期）
│   ├── Document, Chunk, ChunkId, DocumentId
│   └── chunking/       ChunkingStrategy 及各种实现
├── retrieval/          限界上下文②：检索（查询的执行）
│   ├── Retriever, Reranker, FusionStrategy
│   ├── ScoredChunk, RetrievalStage, RetrievalPipeline
└── evaluation/         限界上下文③：评估（质量的度量）
    ├── EvaluationSet, GoldenCase, RetrievedEvidence
    ├── Metric, RecallAtK, MRR, NDCG
    └── DiagnosticReport   ← 聚合根
```

**注意**：三个上下文的通用语言**故意不共用**同一个模型——
知识库说 `Chunk`，检索说 `ScoredChunk`，评估说 `RetrievedEvidence`。
这是防腐层（ACL）的体现，不是冗余。

---

## 5. 技术选型（已定，改动需 ADR）

| 层 | 选型 | 理由 |
|---|---|---|
| JDK | 17（`maven.compiler.release=17`） | AgentScope 2.0 要求 17+ |
| 构建 | Maven 多模块 | — |
| CLI | picocli | 轻量，注解式 |
| 日志 | SLF4J + Logback | — |
| 测试 | JUnit 5 + AssertJ | — |
| 离线 Embedding | `DeterministicHashEmbedding` | CI/单测无需 API Key，指标可复现 |
| 真实 Embedding | 泛化 OpenAI 兼容适配器（可配 `baseUrl`） | 一个适配器通吃 DashScope/DeepSeek/Ollama/vLLM，避免写 N 个 |
| 向量存储 | 内存暴力余弦（MVP）→ pgvector（后期） | 几万条 chunk 暴力搜索足够，且零依赖启动 |
| Agent 层 | `io.agentscope:agentscope-bom`（2.0.4 稳定版） | 已核实 Maven Central 存在，groupId 正确 |

**尚未引入**：Spring Boot（除非有明确需求）、任何向量数据库 SDK、PDFBox 之外的文件格式库。

---

## 6. 编码约定

### 语言

- **代码注释与 Javadoc 用中文**，术语保留英文原词（如 `Chunk`、`Recall@k`）
- **提交信息用中文**，格式：`<type>(<scope>): <描述>`
  - type: `feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `build`
  - 例：`feat(domain): 实现固定窗口切分策略`

### 设计

- 值对象用 `record`，必须**不可变**且**构造时校验**
- 端口接口定义在 domain，实现放 infrastructure
- 每个设计模式的使用必须能回答"**不用它会怎样**"，否则不加
- 禁止为了演示而引入模式

### 测试

- domain 层测试必须**不依赖任何外部资源**
- 测试方法名用 `should_xxx_when_yyy` 风格
- 每个指标实现必须有**手算验证的测试用例**（不能只和自己的实现对比）

---

## 7. 常用命令

```bash
# 构建 + 全部测试（默认离线，无需任何配置）
mvnw.cmd verify            # Windows
./mvnw verify              # Linux/macOS

# 只跑某个模块
mvnw.cmd -pl ragsight-domain test

# 打包 CLI
mvnw.cmd -pl ragsight-cli -am package
java -jar ragsight-cli/target/ragsight-cli.jar --help

# 跳过测试快速编译
mvnw.cmd -q -DskipTests compile
```

**构建前务必确认 `JAVA_HOME` 指向 JDK 17+**（详见 `docs/setup/environment.md`）。

---

## 8. 禁止事项

| 禁止 | 原因 |
|---|---|
| 在 `ragsight-domain` 加第三方依赖 | 违反 R1，架构测试会失败 |
| 在 domain 层调用 LLM 或使用随机数 | 违反 R5，诊断结果不可复现 |
| 把"必须先配 API Key 才能跑"设为默认 | 违反 R4 |
| 引入 Spring Boot | 当前无需求；引入需先写 ADR |
| 实现官方已有的能力（RAG 框架、向量库、Agent 编排平台） | 重复造轮子 |
| 在 README 承诺未实现的功能 | 可信度是第一资产 |
| 用手写的 `.m2/settings.xml` 覆盖用户配置 | 只提供模板，不擅自改用户环境 |

---

## 9. 上下文管理协议（重要）

本项目的开发跨越多个会话，**所有状态必须落盘，不依赖对话记忆**。

### 每次开工

1. 读本文件（AGENTS.md）
2. 读 `PROGRESS.md` —— 尤其是「⏭️ 下一步」和「🧪 已验证的事实」
3. **只读**当前任务相关的 design 文档和目标文件
4. 用 `grep` 定位，**不要通读整个代码库**

### 每次收工

1. `mvnw verify` 必须通过（或明确记录未通过的原因）
2. `git commit`，提交信息写清**为什么**这么改
3. **更新 `PROGRESS.md`**：移入已完成、写明下一步、记录新验证的事实
4. 如有架构决策变更，**新增 ADR**

### 验收证据要求

`PROGRESS.md` 中每个「✅ 已完成」必须附带：
- 实际执行的命令
- 实际的输出结果（不是"应该能跑"）

---

## 10. 相关文档

| 文件 | 内容 |
|---|---|
| `PROGRESS.md` | 进度、下一步、已验证事实、待决策 |
| `docs/setup/environment.md` | 环境要求与实测状态、排查清单 |
| `docs/adr/` | 架构决策记录（为什么这么设计） |
| `docs/design/` | 模块划分、接口设计 |
| `docs/learn/` | 学习文档（每个知识点配可运行验证） |
