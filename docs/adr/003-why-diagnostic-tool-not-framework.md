# ADR-003：为什么不做 Spring Boot 脚手架，而做诊断工具

- **状态**：已接受
- **日期**：2026-10-13
- **决策者**：项目发起人

---

## 背景

项目最初的方向是"基于 AgentScope 的 Java AI Agent 开发脚手架"，包含三根支柱：

1. Spring Boot 声明式装配（YAML 定义 Agent）
2. toB 治理面（多租户、审计、预算、HITL）
3. Agent 回归测试框架

在动手前，我们**实际核查了 AgentScope 官方仓库的模块结构**，结论是这个方向会大面积重复造轮子。

---

## 核查证据

`agentscope-ai/agentscope-java` 仓库（核查时间 2026-10-13）的实际结构：

```
agentscope-extensions/agentscope-spring-boot-starters/
├── agentscope-spring-boot-starter          ← 支柱 1 已被官方覆盖
├── agentscope-admin-spring-boot-starter    ← 支柱 2 部分被覆盖：
│                                              audit/AdminAuditLogger（审计）
│                                              controller/SessionAdminController（会话管理）
│                                              controller/SubagentTaskController（子 Agent 任务）
│                                              dto/PlanModeView（Plan Mode 可观察）
│                                              endpoint/AgentscopeAgentsEndpoint（Actuator 端点）
├── agentscope-agui-spring-boot-starter     ← 前端协议接入
├── agentscope-a2a-spring-boot-starter      ← Agent 间通信
├── agentscope-jev-spring-boot-starter      ← 结构化决策
└── agentscope-{openai,dashscope,anthropic,gemini,ollama,nacos}-spring-boot-starter

agentscope-extensions/
├── agentscope-extensions-rag       ← 5 种 RAG 集成（Simple/Bailian/Dify/RAGFlow/Haystack）
├── agentscope-extensions-judge     ← JEV 结构化决策与评估
├── agentscope-extensions-redis / jdbc / mysql / postgresql / oss / mongodb / cos
├── agentscope-extensions-sandbox   ← Docker/K8s/E2B/Daytona/AgentRun
└── agentscope-extensions-channel   ← 钉钉/飞书/企微/GitHub/GitLab

agentscope-service/                 ← 完整的 Agent 运行时平台（控制面 + 数据面）
```

此外，`agentscope-harness` 的 `DistributedStore` 一行即可自动注入
`AgentStateStore` + `BaseStore` + 沙箱快照 + 分布式执行锁。

**结论**：官方是"全栈"的。支柱 1 完全重复；支柱 2 的审计与会话管理已被 `admin-starter` 覆盖，
剩余部分属于重度定制，通用性存疑；**只有支柱 3（Agent 回归测试）确实是官方空白**。

---

## 决策

**放弃脚手架方向，改做 RAG 质量诊断工具。**

定位为：**回答"RAG 效果不好，瓶颈在哪一段"的诊断工具**，而不是又一个 RAG 框架。

---

## 理由

### 1. 与官方重复是最大的战略风险

如果项目做的是官方已有的能力，会面临三个致命问题：

- **面试必被问**："这跟官方 starter 有什么区别？"——答不出来就是减分
- **无人使用**：官方维护更勤、生态更全，没有理由选第三方的
- **维护负担**：官方一升级就要跟着改，没有终点

### 2. 诊断能力确实是官方空白

官方提供的是 **RAG 的实现**（5 种集成可选）。
但**没有任何工具回答"我的 RAG 哪一段是短板"**。

这个空白是真实的：
- 工程师不知道自己的 RAG 是"已经很好了"还是"还有 20 个百分点可挖"
- 不知道该调切分、换模型、还是加重排
- 只能凭感觉拍参数

### 3. 与个人背景匹配

项目发起人有 8 年 Java + toB 经验，优势在：
- 工程化与架构设计（分层的价值能真正体现）
- 对"生产环境需要什么"的判断力（诊断报告要说清结论是否可信）

而不是在调参和模型原理上比拼。诊断工具恰好是**工程能力密集、算法门槛适中**的方向。

### 4. 该方向天然需要 Agent，而非硬凑

穷举 36 组实验不可行（见 ADR-001），
必须由 Agent 根据中间结果自适应决定下一步——**AgentScope 的接入是需求驱动的，不是为用而用**。

---

## 被否决的方案

### 方案 A：三支柱脚手架（原方案）

- ❌ 支柱 1 与 `agentscope-spring-boot-starter` 完全重复
- ❌ 支柱 2 与 `agentscope-admin-spring-boot-starter` 大量重复
- ❌ 3 个月也未必能到"可用"，而面试准备等不起

### 方案 B：Agent 回归测试框架（只做支柱 3）

- ✅ 差异化最强，确实是官方空白
- ❌ 受众偏窄，且与 AgentScope 的结合度不如依托一个真实场景
- 📌 **不废弃**：作为 P5+ 的演进方向之一保留

### 方案 C：通用 RAG 框架

- ❌ 与官方 `agentscope-extensions-rag` 直接冲突
- ❌ 通用框架赛道已有大量成熟玩家（LangChain / LlamaIndex / Spring AI）

---

## 影响

### 正面

- 差异化明确，能一句话讲清价值
- 场景聚焦，可控范围内容易做到有深度
- AgentScope 的使用是**需求驱动的**，学习更扎实

### 负面 / 代价

- **受众比框架小**：使用人数会少于通用框架
  → 接受：目标是展示能力与深度，不是追求 star 数
- **需要自己构造评测集**才能演示
  → 缓解：用公开文档构建，评测集入仓库可复现

### 对该方向边界的约束

明确**不做**（写入 README 的 Out of Scope）：
- ❌ 不做向量数据库
- ❌ 不做通用 RAG 框架
- ❌ 不做 Agent 编排平台（AgentScope Service 已覆盖）
- ❌ 不做沙箱、不做多渠道接入（无真实业务理由）
- ❌ 不做 Web 产品形态（先做 CLI + 报告）

---

## 相关

- ADR-001：两层架构（诊断引擎 + Agentic 诊断器）
- `AGENTS.md` 第 1 节、第 8 节
