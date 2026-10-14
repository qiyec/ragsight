# ragsight

**RAG 质量诊断与调优工作台 —— 定位检索增强生成的真实瓶颈。**

> 不是又一个 RAG 框架，而是一个**诊断工具**。

---

## 它解决什么问题

所有做过 RAG 的人都遇到过"效果不好"，但几乎没人能回答一个更基本的问题：

> **瓶颈到底在哪一段？**

是切分把答案切断了？是召回根本没捞到？还是重排把本来排第一的正确答案压下去了？

凭感觉调参的结果通常是：改了半天切分粒度，而真正的瓶颈其实在**只用了向量召回**。

ragsight 把 RAG 拆成**可独立度量的阶段**，逐段给出指标，直接定位瓶颈：

```
文档 ──→ [解析] ──→ [切分] ──→ [向量化] ──┐
                                          ├──→ [检索] ──→ [重排] ──→ [上下文装配] ──→ 生成
问题 ─────────────────────────────────────┘
         │
         └──→ 逐段埋点，产出诊断报告

  ┌──────────────────────────────────────────────────────┐
  │  阶段             Recall@5    MRR    诊断             │
  │  仅向量召回         0.62     0.41   ← 这里是瓶颈      │
  │  + BM25 混合检索    0.81     0.55   ✅ Recall +19pt    │
  │  + 重排             0.81     0.78   ✅ MRR    +23pt    │
  │  切分 512→256      0.83     0.80   ✅ 仅 +2pt，不值得  │
  └──────────────────────────────────────────────────────┘
```

最后一行才是诊断的价值：**告诉你哪些改动不值得做**。

---

## 为什么不做成 RAG 框架

因为框架那层已经有成熟方案，再造一个是重复劳动。项目在动手前实际核查了
[AgentScope Java](https://github.com/agentscope-ai/agentscope-java) 官方仓库的模块结构：

```
agentscope-extensions/
├── agentscope-spring-boot-starters/     ← 官方已有 Starter（含 admin 治理面）
├── agentscope-extensions-rag/           ← 官方已有 5 种 RAG 集成
│                                            (Simple / Bailian / Dify / RAGFlow / Haystack)
├── agentscope-extensions-judge/         ← JEV 结构化决策与评估
└── ...
```

官方提供的是 **RAG 的实现**。但**没有任何工具回答"我的 RAG 哪一段是短板"**。
ragsight 做的是这个空白。完整论证见 [ADR-003](docs/adr/003-why-diagnostic-tool-not-framework.md)。

---

## 架构：两层，职责严格分离

```
┌─────────────────────────────────────────────────────────────┐
│ 第二层：Agentic 诊断器（基于 AgentScope）        [规划中]     │
│ 「自主做实验的调试助手」                                      │
│                                                              │
│  全组合实验是组合爆炸（6切分 × 3检索 × 2重排 = 36 组），      │
│  静态穷举成本不可接受 → 必须由 Agent 看结果决定下一步试什么   │
└───────────────────────┬─────────────────────────────────────┘
                        │ 作为 Tool 调用
┌───────────────────────▼─────────────────────────────────────┐
│ 第一层：诊断引擎（纯 Java，六边形架构）          [开发中]     │
│ 「确定性的度量仪」——每个实验都是纯函数                       │
│                                                              │
│   chunk / index / retrieve / rerank / evaluate / report     │
└─────────────────────────────────────────────────────────────┘
```

**为什么必须分两层**：

- 诊断结论必须**可复现**。同一个知识库、同一个评测集，跑两次必须得到同样的数字。
  因此指标计算**绝不能引入 LLM** —— 否则工具本身就不可信。
- 但组合爆炸需要**自适应决策**，事先编排不出来。这是 Agent 的适用场景。

两者不能混：指标算错没人知道；编排写死则诊断不够深入。
完整论证见 [ADR-001](docs/adr/001-two-layer-architecture.md)。

---

## 模块结构

```
ragsight-parent
├── ragsight-domain               纯 Java 领域层，零第三方运行时依赖
│   ├── knowledge/                限界上下文①：知识库（文档生命周期）
│   ├── retrieval/                限界上下文②：检索（查询执行）
│   └── evaluation/               限界上下文③：评估（质量度量）
├── ragsight-application          用例编排，只依赖 domain
├── ragsight-infrastructure       适配器：实现 domain 定义的端口
├── ragsight-cli                  picocli 命令行入口
└── ragsight-architecture-tests   用 ArchUnit 守护架构铁律
```

**依赖方向**：`cli → infrastructure → application → domain`，绝不反向。

---

## 快速开始

**环境要求**：JDK 17+ 、Maven 3.8+（仅 Maven Wrapper 需要，仓库已自带）

```bash
git clone https://github.com/qiyec/ragsight.git
cd ragsight

# Windows
mvnw.cmd verify

# Linux / macOS
./mvnw verify
```

**预期结果**：`BUILD SUCCESS`，全部测试通过。

> **这是本项目的核心设计承诺：默认路径零配置。**
> 不需要 API Key、不需要数据库、不需要 Docker、不需要联网（依赖已在本地仓库时）。
> 所有外部能力（真实模型、向量库、PDF 解析）都是 **opt-in**，不影响默认构建。

---

## 当前状态

> ⚠️ **项目处于早期开发阶段，尚不具备可用的诊断功能。** 以下为诚实进度。

| 模块 | 状态 |
|---|---|
| 领域层三上下文 | ✅ 已完成骨架与核心算法 |
| 切分策略 | ✅ 固定窗口、递归字符（2 / 5 种） |
| 检索指标 | ✅ Recall@k、MRR（手算验证） |
| 多路融合 | ✅ RRF（倒数排名融合） |
| 架构门禁 | ✅ 5 条 ArchUnit 规则 |
| nDCG 指标 | ⬜ 规划中 |
| 向量检索 / BM25 / 重排实现 | ⬜ 规划中 |
| CLI 诊断命令 | ⬜ 规划中（当前仅骨架） |
| Agentic 诊断器 | ⬜ 规划中 |

进度详情与下一步见 [`PROGRESS.md`](PROGRESS.md)。

---

## 设计原则

| # | 原则 | 原因 |
|---|---|---|
| **R1** | `ragsight-domain` 零第三方运行时依赖 | 核心算法必须能在无网络、无数据库、无 API Key 下完整单测 |
| **R2** | `ragsight-application` 只依赖 `domain` | 用例编排不该知道具体向量库/模型 |
| **R3** | 外部能力以接口（端口）表达，实现在 infrastructure | 六边形架构，换实现零成本 |
| **R4** | 默认路径零配置可用 | clone 后 `mvn verify` 必须全绿 |
| **R5** | 诊断结果必须可复现 | 确定性算法，domain 层禁止引入随机性 |

R1 / R2 / R3 / R5 由 [ArchUnit 规则](ragsight-architecture-tests/src/test/java/cn/qiyec/ragsight/architecture/ArchitectureRulesTest.java)
自动守护，**违反即构建失败**，不依赖人工 review。

---

## 文档

| 文档 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | 项目定位、架构铁律、编码约定、上下文管理协议 |
| [`PROGRESS.md`](PROGRESS.md) | 进度、下一步、已验证事实、已知技术债 |
| [`docs/adr/`](docs/adr/) | 架构决策记录（每个决策的权衡与被否决方案） |
| [`docs/setup/environment.md`](docs/setup/environment.md) | 环境要求与排查清单 |

### 架构决策记录（ADR）

| ADR | 决策 |
|---|---|
| [001](docs/adr/001-two-layer-architecture.md) | 为什么分「确定性引擎 + Agentic 诊断器」两层 |
| [002](docs/adr/002-domain-zero-dependencies.md) | 为什么 domain 层禁止任何第三方依赖 |
| [003](docs/adr/003-why-diagnostic-tool-not-framework.md) | 为什么不做 Spring Boot 脚手架（含官方能力核查证据） |

---

## 明确不做

写在最前面，避免误解与无谓的期待：

- ❌ **不做向量数据库** —— 已有成熟方案
- ❌ **不做通用 RAG 框架** —— 与 AgentScope 官方 `extensions-rag` 直接冲突
- ❌ **不做 Agent 编排平台** —— AgentScope Service 已覆盖
- ❌ **不做文档格式全能支持** —— 先聚焦 Markdown 与 PDF
- ❌ **不做 Web 产品形态** —— 先做 CLI + 报告

---

## 许可证

[Apache License 2.0](LICENSE)
