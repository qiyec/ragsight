# ragsight

**帮你看清 RAG 到底哪里不行。**

不是另一个 RAG 框架，是一个**诊断工具**。

---

## 它干什么

做过 RAG 的人大概都经历过这个场景：效果不好，然后开始猜。

调切分粒度、换 embedding 模型、加重排——一轮轮试下来，有时候好一点，有时候白忙。但很少有人能回答一个更基本的问题：

> 到底是哪一步不行？

ragsight 把检索过程拆成能单独测量的几步，分别给出指标，让你看到瓶颈在哪：

```
文档 ──> 解析 ──> 切分 ──> 向量化 ──┐
                                    ├──> 召回 ──> 重排 ──> 拼上下文 ──> 生成
问题 ───────────────────────────────┘

    每一步都能单独测，所以能定位到底哪一步拖后腿
```

跑一次它给出的东西长这样（数据来自内置的演示文档）：

```
指标对比
| 配置                 | Recall@5 | MRR@5 | nDCG@5 |
|----------------------|----------|-------|--------|
| 仅向量召回 (基准)     | 0.889    | 0.667 | 0.703  |
| 仅关键词召回          | 0.889    | 0.762 | 0.786  |
| 向量+关键词 融合      | 0.889    | 0.690 | 0.733  |

相对基准的变化
| 仅关键词召回         | MRR +0.095 | nDCG +0.083 |  <- 收益最大
| 向量+关键词 融合     | MRR +0.024 | nDCG +0.030 |  <- 没到阈值，不值得做
```

最后一行才是它真正的用处：**告诉你哪些改动不值得做**。很多"优化"增加的复杂度远大于那点收益，但没人量化过，所以就一直加。

---

## 为什么不做成 RAG 框架

因为那一层已经不缺人了。动手之前我翻了 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) 的代码结构：

```
agentscope-extensions/
├── agentscope-spring-boot-starters/     已经有 Starter 了
├── agentscope-extensions-rag/           已经有 5 种 RAG 接入
│                                        (Simple / Bailian / Dify / RAGFlow / Haystack)
└── ...
```

它们提供的是 RAG 的**实现**。但没人回答"我这套 RAG 哪一步是短板"。

ragsight 做的就是这件事。完整的判断过程写在 [ADR-003](docs/adr/003-why-diagnostic-tool-not-framework.md) 里。

---

## 怎么做的：核心算法不依赖任何外部东西

这是整个项目的设计底线，值得单独说。

指标计算（Recall、MRR、nDCG）和检索算法（BM25、RRF 融合）全部放在 `ragsight-domain` 里，**这个模块不引用任何第三方库，只用 JDK 自带的东西**。

好处很实在：

- 能在没有网络、没有数据库、没有 API Key 的机器上完整跑测试和验证
- 算法对不对，可以用手算的数字去核对，不靠"跑起来看着像是对的"
- 换向量库、换 embedding 模型、换向量化方式，都不用动这些代码

实现上就是：需要外部能力的地方（比如调用模型、存向量）定义一个接口，具体实现放到 `ragsight-infrastructure` 里。这样核心算法不知道外部世界长什么样。

指标计算尤其不能引入大模型。同一份文档、同一套测试集，跑两次必须得到同样的数字——如果结论本身不稳定，用户拿它去改生产配置就没道理了。

至于"要不要让大模型来决定下一步试什么"，那是后面的事（见下面的规划）。现在这一层是纯粹的度量工具。

---

## 模块划分

```
ragsight-parent
├── ragsight-domain              核心逻辑，只依赖 JDK
│   ├── knowledge/               文档与切分
│   ├── retrieval/               召回、融合、打分
│   └── evaluation/              指标计算
├── ragsight-application         把几步串成一个完整的用例
├── ragsight-infrastructure      上面那些接口的具体实现
├── ragsight-cli                 命令行入口
└── ragsight-architecture-tests  用测试守住"domain 不许引第三方库"这条规矩
```

依赖方向是单线的：`cli → infrastructure → application → domain`。反过来依赖会直接让构建失败，有自动化测试盯着。

---

## 跑起来

需要 JDK 17 或更高。Maven 不用装，仓库里带了。

```bash
git clone https://github.com/qiyec/ragsight.git
cd ragsight

# Windows
mvnw.cmd verify

# Linux / macOS
./mvnw verify
```

`verify` 应该全部通过。**不需要 API Key，不需要数据库，不需要 Docker，也不用联网**（依赖已经在本地仓库的情况下）。

这条是硬要求：任何人 clone 下来，不读文档、不配环境就能跑通。所以真实模型、向量数据库这些全都是可选的，不装也不影响默认流程。

### 看它长什么样（内置演示文档）

```bash
mvnw.cmd -pl ragsight-cli -am package
java -jar ragsight-cli/target/ragsight-cli.jar diagnose
```

### 诊断你自己的文档

```bash
java -jar ragsight-cli/target/ragsight-cli.jar diagnose \
     --corpus examples/corpus \
     --eval examples/eval.txt
```

`--corpus` 是文档目录（支持 `.md` / `.markdown` / `.txt`），`--eval` 是测试集文件。

测试集文件长这样，一个用例一块：

```
Q: 账单什么时候生成
E: 次月 3 日前生成

Q: 错误码 E4021 是什么
E: E4021 表示计费账户被冻结

# 只有问题、没有 E，就是负样本——表示这个问题本来就该答不出来
Q: 如何申请退款
```

`E` 写的是**关键文字**，不是片段编号。片段编号取决于切分参数，写死之后一调切分就会指向不存在的片段，指标全变 0，而你多半会以为是检索坏了。写关键文字就没这个问题。

格式说明和完整例子在 `examples/eval-format.txt`。

其它可调项：

```bash
--top-k 5               每种召回取前几条
--chunk-size 90         切分窗口大小（字符）
--chunk-overlap 18      相邻窗口的重叠字符数
-o report.md            把报告写到文件
```

---

## 现在做到哪了

> 项目还在早期。能诊断你自己的文档了，但对比的维度还只有"召回方式"一种。
> 下面如实列出进度。

| 部分 | 状态 |
|---|---|
| 读文档 | 支持 Markdown / 纯文本目录 |
| 文档切分 | 固定窗口、递归字符（两种，计划五种） |
| 召回 | 向量召回、关键词召回（BM25）、两者融合（RRF） |
| 指标 | Recall@k、MRR、nDCG（都是手算验证过的） |
| 诊断报告 | Markdown 格式，含逐条用例的命中位置 |
| 命令行 | `diagnose`，支持内置演示文档和外部目录 |
| 架构测试 | 5 条规则守住依赖方向，另有自检测试确认门禁真的会拦人 |
| 重排 | 只有接口，没有实现 |
| 切分策略对比 | 未开始（现在只能对比召回方式，还不能对比不同切分） |
| 真实向量模型 | 未开始（现在只有离线哈希向量化，只认字面） |
| 让大模型自己决定试什么 | 未开始 |

详细的进度、下一步和已知问题都在 [PROGRESS.md](PROGRESS.md)，那份文件是跨会话交接用的，写得很具体。

---

## 几条我坚持的规矩

| 规矩 | 为什么 |
|---|---|
| 核心模块不引第三方库 | 算法要能离线验证，换实现不用改核心 |
| 默认路径零配置能用 | 配不了环境的工具等于不能用 |
| 结论必须可复现 | 同一输入跑两次结果不同，就没法拿它做决策 |
| 测试和结论都基于实测数字 | 手算或实测过的才写进断言，不靠"感觉对" |

前四条里除"零配置"外，都由 [架构测试](ragsight-architecture-tests/src/test/java/cn/qiyec/ragsight/architecture/ArchitectureRulesTest.java) 自动检查，违反了构建会失败，不靠人记得。

---

## 文档

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 项目定位、要守的规矩、编码习惯 |
| [PROGRESS.md](PROGRESS.md) | 进度、下一步、踩过的坑 |
| [docs/adr/](docs/adr/) | 每个重要决定的原因，以及被否掉的方案 |
| [docs/setup/environment.md](docs/setup/environment.md) | 环境要求和常见问题 |

### 重要决定

| 决定 | 内容 |
|---|---|
| [001](docs/adr/001-two-layer-architecture.md) | 为什么分成"算指标"和"决定试什么"两层 |
| [002](docs/adr/002-no-third-party-in-core.md) | 为什么核心模块不许引第三方库 |
| [003](docs/adr/003-why-diagnostic-tool-not-framework.md) | 为什么不做框架（附官方能力核查） |
| [004](docs/adr/004-bm25-and-tokenizer-decoupling.md) | BM25 放哪、分词怎么解耦 |

---

## 明确不做

先写在前面，省得误会：

- 不做向量数据库
- 不做通用 RAG 框架
- 不做 Agent 组织平台
- 不做所有文档格式的支持（先支持 Markdown 和纯文本）
- 不做 Web 界面（先做好命令行和报告）

---

## 许可证

[Apache License 2.0](LICENSE)
