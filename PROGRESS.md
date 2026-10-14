# PROGRESS.md — 进度与交接日志

> **用途**：跨会话的交接文件。新会话读完 `AGENTS.md` 后立刻读本文件。
> **纪律**：每个「✅ 已完成」必须附带**实际执行的命令 + 实际输出**，不接受"应该能跑"。

- **最后更新**：2026-10-14
- **当前阶段**：P3 CLI 与诊断报告（可诊断外部文档，可对比切分与召回）
- **远程仓库**：https://github.com/qiyec/ragsight （public，CI 全绿）
- **规模**：186 个测试

---

## 🎉 里程碑：端到端诊断链路已打通

```bash
java -jar ragsight-cli/target/ragsight-cli.jar diagnose
```

**实际输出**（内置演示语料，15 个片段，9 个用例，3 种配置）：

```
## 诊断结论
- 语料 15 个片段，评测集 9 个用例（其中负样本 2 个）
- 基准（仅向量召回）Recall@5 = 0.889，召回基本充分，瓶颈更可能在排序环节。
- 以下配置相对基准无可观测收益，建议不要采用：向量+关键词 RRF 融合

## 指标对比
| 配置 | Recall@5 | MRR@5 | nDCG@5 |
|---|---|---|---|
| 仅向量召回 (基准)     | 0.889 | 0.667 | 0.703 |
| 仅关键词召回          | 0.889 | 0.762 | 0.786 |
| 向量+关键词 RRF 融合  | 0.889 | 0.690 | 0.733 |

## 相对基准的变化
| 配置 | MRR@5 | nDCG@5 |
|---|---|---|
| 仅关键词召回         | +0.095 | +0.083 |
| 向量+关键词 RRF 融合 | +0.024 | +0.030 |
```

**这份报告证明了工具的核心价值**：它明确指出"仅关键词召回"在当前语料上
收益最大（MRR +0.095），而 RRF 融合的增益未达显著阈值（+0.024 < 0.05），
**不建议为此增加系统复杂度**——"哪些改动不值得做"与"哪些值得做"同等重要。

---

## ✅ 已完成

### P0 环境与骨架

| 交付物 | 说明 |
|---|---|
| Maven 多模块骨架 | domain / application / infrastructure / cli / architecture-tests |
| Maven Wrapper | 锁定 3.9.9，走阿里云镜像 |
| Enforcer | 强制 JDK 17+ / Maven 3.8+，不满足时中文报错 |
| `.gitattributes` | `mvnw` 强制 LF（否则 Linux 报 bad interpreter） |
| AGENTS.md | 新会话第一读物：定位、规矩、约定、上下文协议 |
| ADR 001-004 | 两层架构 / 核心不引第三方库 / 为何不做框架 / BM25 放哪 |
| README / LICENSE / CI | Apache-2.0；GitHub Actions 矩阵构建（JDK 17 + 21，全绿） |

> ⚠️ **已移除**：曾加过"`~/.m2/toolchains.xml` 存在即激活"的 Toolchains profile，
> 导致 CI 的 JDK 21 job 失败（runner 自带该文件却无 JDK 17，
> profile 被隐式激活 → `Cannot find matching toolchain definitions`）。
> 教训：**用隐式文件存在性做构建开关是脆弱的**——本地通过、CI 失败，
> 且原因与业务代码无关。已彻底移除，回归 Enforcer 显式校验。

### P1/P2 领域层与检索管道

| 层 | 内容 |
|---|---|
| **knowledge** | `Document` / `Chunk` / `ChunkId` / `DocumentId`；`FixedWindow`、`RecursiveCharacter` 两种切分策略 |
| **retrieval** | `ScoredChunk` / `RetrievalSource`；接口 `Retriever` / `Reranker` / `EmbeddingModel` / `VectorIndex` / `Tokenizer`；算法 `ReciprocalRankFusion`（RRF）、`Bm25Scorer`（BM25） |
| **evaluation** | `GoldenCase` / `EvaluationSet`（核心类）/ `RetrievedEvidence`；指标 `RecallAtK` / `MRR` / `NDCG`（全部手算验证） |
| **application** | `DiagnosisEngine`（诊断用例）/ `RetrieverConfig` / `DiagnosisRequest` / `DiagnosisReport` / `ExperimentResult` / `CaseOutcome`；接口 `RetrieverFactory` |
| **infrastructure** | `DeterministicHashEmbedding`（离线向量化）/ `InMemoryVectorIndex` / `VectorRetriever` / `Bm25Retriever` / `HybridRetriever` / `TextTokenizer` / `SimpleTokenizer` / `CompositeRetrieverFactory` |
| **cli** | `RagSightCli` + `DiagnoseCommand`；`DemoCorpus` + `DemoEvaluationSet` + `ReportRenderer`（Markdown） |
| **architecture-tests** | 5 条 ArchUnit 规则，把规矩变成构建门禁 |

**测试分布**：domain 80 / application 16 / infrastructure 83 / architecture 7 = **186 个，全部通过**。

---

## 🐛 本阶段修复的缺陷（含 3 个由测试/实测抓出的真实 bug）

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| 1 | **切分窗口过大导致诊断失去意义** | 演示语料用 window=300，每篇文档只切出 1 片 → 语料共 5 片而 topK=5，等于"全部返回"，Recall 恒为 1.0 | 改为 window=90/overlap=18，切出 15 片。**这个教训本身就是诊断工具要传达的：切分过大会让检索"看起来永远正确"** |
| 2 | **RRF 融合反而比单路差**（MRR 0.690 < 0.762） | 候选池太小：期望片段在两路中都排在候选截断之外，融合阶段无法找回 | 把候选倍数做成可配参数（默认 3→6），并在类文档中诚实记录这个 RRF 局限 |
| 3 | 评测集标注失效 | 语料写在 Java 文本块里，**行尾空格被剔除**，标注写了「90% 时」而实际是「90%时」 | 标注改用短特征文本（如「达到配额的」），对格式变化更稳健 |
| 4 | BM25 手算测试三处断言错误 | 手算舍入误差 + 直觉错误（误以为"长文档一定吃亏"，实测 docB tf=3 收益胜过长度惩罚） | 先加探针测量真实值再定断言；实现与公式复现值逐位相同 |
| 5 | 离线 embedding 能力边界断言错误 | 误以为「计费规则」与「收费规则」相似度低，实际共享 3 字得 0.75 | 按实测值重写；补「计费/收费 = 0.5」精确暴露无语义泛化能力 |

---

## ⏭️ 下一步（精确到文件）

1. **`Reranker` 实现 + `LexicalOverlapReranker`**（最优先）
   —— 接口已有、无实现。报告现在会说"瓶颈在排序、该试重排"，
   但用户没法真的试，这是个明显的缺口。
2. **真实向量模型适配器**
   —— 现在只有离线哈希向量化，只认字面重合，绝对分数没有参考价值。
   接一个通用的 OpenAI 兼容适配器（可配 baseUrl）就能对接
   DashScope / DeepSeek / Ollama，前提是要有 API Key。
3. **HTML 报告**（Markdown 已够用，优先级低于上面两项）
4. **`docs/learn/` 学习文档**：每个知识点配可运行验证

---

## 🧪 已验证的事实（命令 + 实际输出）

| # | 验证内容 | 命令 | 实际结果 |
|---|---|---|---|
| 1 | AgentScope 坐标为 `io.agentscope` | `gh api .../agentscope-core/pom.xml` | 确认 |
| 2 | **AgentScope 基线是 Java 17** | `gh api .../agentscope-java/main/pom.xml` | `<java.version>17</java.version>` → 本项目统一 17，Agent 层不需要 21 |
| 3 | 全局 settings.xml 启用阿里云镜像 | Maven 构建日志 | `Downloaded from aliyun-central` |
| 4 | 本地仓库位置 | 读 `conf/settings.xml` | `D:\develop\environment\Maven\repo` |
| 5 | 无 SDKMAN；有 Scoop/fnm/uv | `Get-Command sdk` 等 | Scoop 仅装 git/7zip/neofetch |
| 6 | `mvnw` 换行符 | `git ls-files --eol mvnw` | `i/lf w/lf attr/text=auto eol=lf` |
| 7 | 全量构建 | `mvnw.cmd clean verify`（JAVA_HOME 置空） | 退出码 0，186 个测试全通过 |
| 8 | **诊断报告可产出** | `java -jar ragsight-cli.jar diagnose` | 输出完整 Markdown 报告（见上方里程碑） |
| 9 | 离线向量化实测相似度 | `DeterministicHashEmbeddingTest` | 计费规则/收费规则=0.7500；计费/收费=0.5000；无交集=0.0000 |
| 10 | BM25 手算一致性 | `Bm25ScorerTest` | IDF=ln(10/7)=0.356674943938732 精确一致；docA=0.519658858718683 |

---

## 📌 已知问题 / 技术债

| # | 问题 | 影响 | 计划 |
|---|---|---|---|
| 1 | ~~`archunit.properties` 的 `failOnEmptyShould`~~ | ~~架构规则若被写空不再报警~~ | ✅ 已改回 `true`，并加 `ArchGateSelfCheckTest` 验证门禁真的会拦人 |
| 2 | **无 application 层测试** | 诊断结论规则未被验证 | 下一步（元评测雏形） |
| 3 | 只能用内置演示语料 | 无法诊断真实知识库 | 接入 `--corpus <path>` |
| 4 | 切分策略尚不能作为实验变量 | 报告无法回答"该不该改切分" | 需要重构请求模型 |
| 5 | 无 `Reranker` 实现 | 报告无法建议"该不该加重排" | 实现 `LexicalOverlapReranker` |
| 6 | 离线 embedding 无真实语义能力 | 绝对指标不代表真实模型 | 已接入接口；需真实模型适配器（可选） |
| 7 | 无 `docs/learn/` 学习文档 | 学习价值未落地 | 后续 |
| 8 | 演示语料规模小（15 片段） | 指标区分度有限 | 接入外部语料后自然解决 |

---

## ❓ 待决策

| # | 事项 | 状态 |
|---|---|---|
| 1 | 仓库名 `ragsight` | ✅ 已确认 |
| 2 | 推送到 GitHub | ✅ 已完成 |
| 3 | BM25 归属 | ✅ ADR-004：算法在 domain，分词抽成接口 |
| 4 | 评测集用哪份公开语料 | ⏳ 建议用 ragsight 自己的文档（AGENTS.md + ADR），自举且可复现 |
| 5 | 是否需要真实 embedding 适配器 | ⏳ 视是否有可用 API Key |

---

## 🔗 相关文档

| 文件 | 内容 |
|---|---|
| `AGENTS.md` | 项目定位、规矩、编码约定、上下文管理协议 |
| `docs/adr/001-two-layer-architecture.md` | 为什么分两层 |
| `docs/adr/002-no-third-party-in-core.md` | 为什么核心模块不引第三方库 |
| `docs/adr/003-why-diagnostic-tool-not-framework.md` | 为什么不造框架（含官方能力核查证据） |
| `docs/adr/004-bm25-and-tokenizer-decoupling.md` | BM25 归属与分词抽成接口 |
| `docs/setup/environment.md` | 环境要求、实测状态、排查清单 |
