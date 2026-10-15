# PROGRESS.md — 进度与交接日志

> **用途**：跨会话的交接文件。新会话读完 `AGENTS.md` 后立刻读本文件。
> **纪律**：每个「✅ 已完成」必须附带**实际执行的命令 + 实际输出**，不接受"应该能跑"。

- **最后更新**：2026-10-14
- **当前阶段**：P5 第二层已完成（诊断 Agent 会自己决定试什么）；下一步：用真实 Key 实测 Agent
- **远程仓库**：https://github.com/qiyec/ragsight （public，CI 全绿）
- **规模**：275 个测试

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

**测试分布**：domain 90 / application 16 / infrastructure 92 / architecture 7 = **205 个，全部通过**。

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

1. **真实向量模型适配器**（最优先）
   —— 现在只有离线哈希向量化，只认字面重合，绝对分数没有参考价值。
   接一个通用的 OpenAI 兼容适配器（可配 baseUrl）就能对接
   DashScope / DeepSeek / Ollama。**需要 API Key 才能实测**，
   没有的话只能写代码 + 用假客户端测。
2. **HTML 报告**（Markdown 已够用，优先级低于上面那项）
3. **`docs/learn/` 学习文档**：每个知识点配可运行验证

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
| 7 | 全量构建 | `mvnw.cmd clean verify`（JAVA_HOME 置空） | 退出码 0，205 个测试全通过 |
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

---

## 第二层（诊断 Agent）—— 已完成，但**没有真实环境实测**

新增 `ragsight-agent`，基于 AgentScope 2.0.4。核心是**每个工具只改一个东西**：
`compare_one_change` 每次只动一个参数，所以效果能归因。

| 项 | 状态 |
|---|---|
| 工具集（run_baseline / compare_one_change / list_attempts / finish） | ✅ 编译通过 |
| 系统提示词（要求一次只改一个东西） | ✅ |
| 进度上报（AgentScope Hook） | ✅ |
| 架构规则（不依赖 agent / 不依赖 agentscope 等 7 条） | ✅ 已验证会拦人 |
| 命令行入口 `diagnose` | ✅ `--list` 和缺 Key 提示已实测 |
| 工具逻辑测试（不调大模型） | ✅ **33 个**，抓出 3 个真 bug |
| 工具注册 schema 检查 | ✅ 6 个，确认 @Tool 反射注册有效 |
| **Agent 循环（假模型驱动）** | ✅ **7 个**，确认工具真的被执行、结果真的回传 |
| 网页上的 Agent 入口（SSE 实时推送） | ✅ 接口实测，缺 Key 时正确报错 |
| **真实调用大模型跑通** | ❌ **未验证**，需要 `DASHSCOPE_API_KEY` |

### 下一步要做的第一件事

```bash
set DASHSCOPE_API_KEY=你的key
# 然后跑一次真实诊断，确认 Agent 真的会按预期做单变量对比，
# 而不是退化成穷举
```

**未验证的部分要说清楚**：工具的逻辑、提示词、Hook 都只做到了编译通过和架构合规，
没有真正让大模型跑过。第一次跑可能会暴露：
- 模型不按"一次只改一个"的要求做
- 工具返回值太长或格式不合适，模型读不懂
- maxIters=12 不够或太多

### 这一步踩到的坑（两个都值得记）

1. **"编译通过"可能是假象。**
   最初让 agent 依赖 web，`mvnw -pl ragsight-agent -am compile` 通过了。
   但那是 `-am` 让 web 以模块形式参与反应堆，classes 目录恰好在 classpath 上。
   独立构建立刻失败：Spring Boot 打的是可执行胖包，类在 `BOOT-INF/classes` 下，
   根本不能当依赖。**结论：验证跨模块依赖时不能只看 `-am` 的构建结果。**

2. **Spring Boot 的胖包会让 ArchUnit 失效。**
   新加的"web 不应依赖 agent"规则报 `failed to check any classes`。
   原因是 ArchUnit 读不了 `BOOT-INF/classes` 里的类。
   修法是给 `spring-boot-maven-plugin` 加 `classifier=exec`，
   主产物回到普通薄 jar。

   注意这个规则**报错了而不是静默通过**——因为架构测试设了
   `failOnEmptyShould(true)`。这条设置救了一次。
---

## 决策过程怎么在网页上看

`http://localhost:8080` 左侧底部有"让 Agent 自己找瓶颈"。
点下去后上方会出现实时日志：Agent 的每一步、调了哪个工具、工具返回什么，
一路推下来。最后给结论卡。

**为什么用 SSE**：这是单向的（服务端推、客户端听），
SSE 就是为这个场景设计的，Spring MVC 直接有 `SseEmitter`。
WebSocket 是双向的，这里用不上。

**前端用 fetch 读流而不是 EventSource**：EventSource 只支持 GET，
而这里要 POST 传参数。

### 为什么这块单独搞个线程池

Agent 跑一次是几十秒级的阻塞调用，不能占着 Tomcat 的请求线程。
线程池固定 2 个，刻意小——每个 Agent 都在真花钱调模型，
排队比并发合适。

### 一个容易漏的地方

`SseEmitter` 必须注册 `onCompletion` / `onTimeout`。
不注册的话浏览器关掉页面后，后台还在闷头跑，
**在真花钱和真的跑实验**。这不是"可选的好习惯"。
---

## 假模型测试：把"Agent 循环"也钉住了

之前只测了工具本身，但"模型要调工具 → Agent 真的去调 → 结果回传"这段
**一次都没验过**。它属于最典型的静默失效：不报错，只是 Agent 聊几句就结束。

现在用 `ScriptedModel`（一个按剧本走的 `Model` 实现）驱动真实循环，
不用 Key、不花钱、完全确定。覆盖 7 件事：

- 工具真的被执行
- 结果真的回传模型
- 连续多个工具能串起来
- 工具报错不崩 Agent
- 没跑基准时给可读提示
- `maxIters` 真的拦住无限循环
- 进度 Hook 真的触发

### 两个把人带偏的坑（都记在测试注释里）

1. **`ToolUseBlock.content` 必须填。**
   它是模型返回的原始参数 JSON。不填时报的是
   `argument "content" is null`，而不是"chunk_size 缺了"——
   这个错误信息把我往"参数名写错"的方向带了整整三轮。

2. **`Msg` / `ToolUseBlock` / `ToolResultBlock` 都没重写 `toString()`。**
   直接打印是对象身份（`ToolResultBlock@65ae095c`）。
   断言对话内容必须一层层拆到底：
   `msg.getTextContent()` → `Msg.getContent()` 里的 `TextBlock`
   → `ToolResultBlock.getOutput()`。

### 顺带确认

**框架的参数校验是生效的**，而且校验失败时会把
"参数验证失败，缺少 xxx，请修正后重试"作为工具结果**回传给模型**——
模型有机会自己改对。这是好设计，值得知道。
---

## 「不穷举」到底省了多少——实测数字

这是目标的核心断言，所以要说清楚，不能含糊带过。

用假模型驱动一条**真实的自适应路径**（基准 → 试重排 → 试换召回 → 试改切分 → 收尾），
实测累计 **10 次实验**，而全穷举这个语料是 **18 组**
（3 种切分 × 3 种召回 × 2 种重排）。

注意"3 切分 × 3 召回 = 9"这个算法是错的——重排那一维是每个切分各自加一组，
所以总数是 3 × 3 × 2 = 18。「一次实验」=（一种切分 + 一种召回 + 一种重排开关）跑一遍测试集。

### 关键的证据不是"次数少"，而是这一条

自适应流程最后停在 **「递归切分 200 字 · 仅关键词召回」**，MRR 0.938。
**这一组在基准里根本没出现过**（基准只有 90 字那一档）——

它是一步步走出来的：基准最好的那档是"90 字 + 关键词"（MRR 0.813），
试"加重排"没用（0.604），试"切分改成 200 字"才跳到 0.938。

这才是自适应探索的价值：**不是把已知的几档跑一遍，而是顺着中间结果走到没试过的地方。**
测试里把这条钉住了（断言探索后的 MRR > 0.813）。

### 什么时候省得更多

当前语料小（3 种切分可选），自适应每次对比要重跑一遍"改之前"，
开销占比不低。维度一多差距就拉开了：

| 维度 | 全穷举 | 自适应（典型） |
|---|---|---|
| 3 切分 × 3 召回 × 2 重排 | 18 组 | 约 10 次 |
| 6 切分 × 3 召回 × 2 重排 | 36 组 | 仍然约 10 到 16 次 |

自适应次数的增长远慢于维度增长——因为它每次只动一个。