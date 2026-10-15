# AGENTS.md — 新会话第一读物

> 先读这个，再读 `PROGRESS.md`（那里写着干到哪了、下一步做什么）。
> 这里回答"这是个什么项目、有什么规矩"。

---

## 1. 这个项目是什么

**ragsight：帮人看清 RAG 到底哪里不行的诊断工具。**

做过 RAG 的人都会遇到"效果不好"，但很少有人能说清是**哪一步**不行：
切分把答案切断了？召回根本没捞到？还是重排把本来排第一的挤下去了？

ragsight 把检索拆成能单独测量的几步，分别给指标，直接指出瓶颈。

**它不是 RAG 框架。** AgentScope 官方已经有 `agentscope-extensions-rag`
（5 种接入方式），再做一个框架是重复劳动。ragsight 做的是官方没做的部分：
量化诊断和对比实验。

---

## 2. 分两层，别搞混

```
┌───────────────────────────────────────────────────────┐
│ 第二层：让大模型自己决定试什么                [已完成]  │
│  ragsight-agent                                        │
│                                                        │
│ 为什么需要：把所有组合跑一遍是跑不完的                  │
│ （3种切分 × 3种召回 × 2种重排 = 18 组，还得跑测试集）   │
│ 它一次只改一个东西、看结果、再决定下一个改什么          │
└──────────────────────┬────────────────────────────────┘
                       │ 作为一个工具被调用
┌──────────────────────▼────────────────────────────────┐
│ 第一层：算指标、跑对比                        [已完成]   │
│  ragsight-domain / application / diagnosis             │
│ 「尺子」——每个实验都是纯计算，结果必须可复现            │
└───────────────────────────────────────────────────────┘
```

**第一层绝对不能出现大模型调用。** 同一份文档、同一套测试集，跑两次必须
得到一模一样的数字。如果指标是靠模型算的，那这个工具给的结论就没人敢用。

第二层只用大模型决定"试什么"，**不下结论**——结论仍由第一层的固定规则推导。
所以同一份数据不会因为模型这次发挥不同而给出两种说法。

---

## 3. 模块和依赖方向

```
ragsight-parent
├── ragsight-domain              核心逻辑，只依赖 JDK
├── ragsight-application         把几步串成一个完整用例
├── ragsight-infrastructure      接口的具体实现
├── ragsight-diagnosis           网页和 Agent 共用的那段编排（读语料、跑方案）
├── ragsight-cli                 命令行入口
├── ragsight-web                 Web 界面（Spring Boot）+ SSE 推 Agent 过程
├── ragsight-agent               第二层：会自己决定试什么的诊断 Agent
└── ragsight-architecture-tests  用测试守住下面的规矩
```

依赖方向（箭头单向，有架构测试守着）：

```
cli ─┐
     ├─▶ infrastructure ─┐
web ─┤                    ├─▶ application ─▶ domain
     └─▶ agent ──▶ diagnosis ─┘
```

- `diagnosis` 是**中立**的：不认识 Spring、不认识网页、不认识 Agent
- `web` 依赖 `agent`（网页上有 Agent 入口），但反过来不行
- `cli` 和 `agent` 是平级入口，互不依赖


### 必须守住的规矩

| # | 规矩 | 为什么 |
|---|---|---|
| **R1** | `ragsight-domain` 不许引任何第三方库 | 核心算法要能在没网络、没数据库、没 Key 的机器上验证 |
| **R2** | `ragsight-application` 只能依赖 `ragsight-domain` | 用例流程不该知道用的是哪个向量库、哪个模型 |
| **R3** | 需要外部能力的地方定接口，实现放 `ragsight-infrastructure` | 换实现不用改核心代码 |
| **R4** | 默认流程零配置可用 | clone 下来 `mvn verify` 必须全绿，不用配任何东西 |
| **R5** | 结论必须可复现 | core 里不许用随机数，也不许调模型 |

> R1 / R2 / R3 / R5 由 `ragsight-architecture-tests` 的 ArchUnit 测试自动检查，
> 违反了构建就失败，不靠人记得。

---

## 4. 包结构

```
cn.qiyec.ragsight.domain
├── knowledge/     文档和切分
│   ├── Document, Chunk, ChunkId, DocumentId
│   └── chunking/  ChunkingStrategy 和几种实现
├── retrieval/     召回、融合、打分
│   ├── Retriever, Reranker, EmbeddingModel, VectorIndex, Tokenizer
│   ├── ScoredChunk, RetrievalSource
│   ├── Bm25Scorer            (纯算法)
│   └── ReciprocalRankFusion  (纯算法)
└── evaluation/    指标计算
    ├── GoldenCase, EvaluationSet, RetrievedEvidence
    ├── Metric, RecallAtK, MRR, NDCG
```

**三个模块各说各的话，不硬凑一个万能对象。**
比如文档模块管它叫 `Chunk`，召回模块关心"它排第几、从哪条路来的"所以有
`ScoredChunk`，评估模块只关心"排在第几"所以只留 `RetrievedEvidence`。

看起来有点像重复，但这是故意的：如果只用一个对象贯穿全程，
**评估指标就会顺手去读分数**——而不同召回器的分数根本不可比
（余弦相似度在 -1~1，BM25 是无上界的正数）。从结构上杜绝这类错误，
比写注释提醒有效得多。

---

## 5. 技术选型（要改先写 ADR）

| 地方 | 用的什么 | 为什么 |
|---|---|---|
| JDK | **17** | AgentScope 官方基线也是 17，全项目统一，不需要 21 |
| 构建 | Maven 多模块 | — |
| 命令行 | picocli | 轻，注解式 |
| 日志 | SLF4J + Logback | — |
| 测试 | JUnit 5 + AssertJ + ArchUnit | — |
| 离线向量化 | `DeterministicHashEmbedding` | 让测试和 CI 不需要 API Key，指标可复现 |
| 真实向量化 | 一个通用的 OpenAI 兼容适配器（可配 `baseUrl`） | 一个实现能对接 DashScope / DeepSeek / Ollama / vLLM，不用写一堆 |
| 向量存储 | 内存暴力算（先这样），后期考虑 pgvector | 几万个片段暴力算也就毫秒级；而且不用起 Docker 就能跑 |
| Web 层 | Spring Boot 3.5.9（只加在 `ragsight-web`） | 理由和被否掉的方案见 `docs/adr/005-web-layer-and-spring-boot.md` |
| Agent 层 | AgentScope 2.0.4（`agentscope-core` + 模型扩展） | 用 2.0.4 不用 2.1.0-BETA1——这是给别人用的项目，不押 BETA |

**还没引入**：任何向量数据库 SDK、PDFBox 之外的文件解析库。
引入任何一个都要先写 ADR 说明为什么。

注意 Spring Boot 和 AgentScope 都是**边缘依赖**：只在 `ragsight-web`
和 `ragsight-agent` 里。domain 和 application 依然零第三方依赖，
有架构测试盯着（12 条规则）。

---

## 6. 写代码的习惯

### 语言

- **注释和文档都用中文，说人话。** 不要为了显得专业造词。
  能说"换个实现不用改核心代码"就不要写"依赖倒置"。
- 术语该用还是用，但**第一次出现时要用一句话解释清楚**，不能让读者先学会
  一套黑话才能看懂。
- 提交信息用中文，格式 `<type>(<scope>): <描述>`
  - type 用 `feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `build`
  - 例：`feat(domain): 实现固定窗口切分`

### 设计

- 数据类用 `record`，不可变，构造时就校验参数
- 需要外部能力的地方定接口，实现放 infrastructure
- **每用一个设计模式，都要能回答"不用它会怎样"。回答不出来就别用。**
- 没被用到的方法别留着"以后可能有用"——真需要时再加

### 测试

- domain 的测试不许依赖任何外部资源
- 测试方法名用 `should_xxx_when_yyy`
- **指标和算法的测试必须手算或实测验证过**，不能拿实现自己的输出当预期值
  （那样只能证明"代码没改"，证明不了"算得对"）
- 定阈值前先测真实值。凭直觉猜阈值会导致"实现是对的、断言是错的"，
  这种错误最浪费时间

---

## 7. 常用命令

```bash
# 构建 + 全部测试（默认离线，不用配任何东西）
mvnw.cmd verify            # Windows
./mvnw verify              # Linux/macOS

# 只跑某个模块
mvnw.cmd -pl ragsight-domain test

# 打包并跑诊断
mvnw.cmd -pl ragsight-cli -am package
java -jar ragsight-cli/target/ragsight-cli.jar diagnose

# 跳过测试快速编译
mvnw.cmd -q -DskipTests compile
```

构建前确认 `JAVA_HOME` 指向 JDK 17+（细节见 `docs/setup/environment.md`）。

---

## 8. 别做的事

| 别做 | 为什么 |
|---|---|
| 在 `ragsight-domain` 加第三方库 | 违反 R1，架构测试会失败 |
| 在 domain 里调模型或用随机数 | 违反 R5，结论就不可复现了 |
| 让默认流程变成"必须先配 Key 才能跑" | 违反 R4 |
| 没需求就引入 Spring Boot | 引入要先写 ADR |
| 实现官方已经有的东西 | 重复劳动 |
| README 里承诺还没做的功能 | 可信度是第一位的，功能少不丢人，吹牛才丢人 |
| 改用户机器上的配置文件 | 只提供模板，不擅自改 |

---

## 9. 跨会话怎么交接（重要）

这个项目会跨很多次会话开发，**所有状态都要落盘，不能靠对话记忆**。

### 每次开工

1. 读这个文件
2. 读 `PROGRESS.md`，重点看「下一步」和「已验证的事实」
3. **只读**当前任务相关的文件
4. 用 grep 定位，不要通读整个代码库

### 每次收工

1. `mvnw verify` 要能通过（通不过就明确记下原因）
2. `git commit`，提交信息写清楚**为什么**这么改
3. **更新 `PROGRESS.md`**：把做完的挪进去、写清下一步、记下新验证的事实
4. 有架构决策变化就新加一篇 ADR

### 关于"已完成"的标准

`PROGRESS.md` 里每条「✅ 已完成」都必须带上：
- 实际执行的命令
- 实际的输出

**"应该能跑"不算完成。**

---

## 10. 相关文件

| 文件 | 内容 |
|---|---|
| `PROGRESS.md` | 进度、下一步、踩过的坑 |
| `docs/setup/environment.md` | 环境要求和常见问题 |
| `docs/adr/` | 重要决定的原因，以及被否掉的方案 |
| `docs/design/` | 模块划分和接口设计 |
| `docs/learn/` | 学习文档（每个知识点配一个能跑的验证） |
