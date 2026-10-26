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

### 方式一：浏览器里用（推荐，能看热力图）

```bash
# 打包一次，两个可执行 jar 会分别产在各自模块的 target 下
mvnw.cmd clean package -DskipTests

# 启动服务
java -jar ragsight-web/target/ragsight-web-exec.jar
```

等它打印出 `Started RagsightWebApplication` 之后，**在浏览器地址栏输入**：

```
http://localhost:8080
```

> ⚠️ **注意是"在地址栏输入这个地址"，不是打开 `index.html` 文件。**
> 直接双击 HTML 文件的话地址是 `file:///...`，页面请求不到后端接口。
> 真这么开了页面也会提示你怎么做。

页面上可以调语料、切分窗口、召回条数，勾选要不要对比重排，点"跑诊断"。
结果会画成热力图——哪个组合好、切分有多大影响，一眼能看出来。

### 方式三：让 Agent 自己找瓶颈（需要大模型凭据）

上面两种方式都是**你把组合定好、它去跑**。这个方式是**它自己决定试什么**：
先跑基准，看结果，再决定下一步改哪一个，通常 3 到 4 轮就够——
而不是把 18 种组合跑一遍。

```bash
# 先把凭据放环境变量（DashScope 是这个名字）
$env:DASHSCOPE_API_KEY = "你的key"        # Windows PowerShell
export DASHSCOPE_API_KEY=你的key           # Linux / macOS

java -jar ragsight-agent/target/ragsight-agent-exec.jar diagnose
```

加上 `--list` 看有哪些语料可选，加 `-q` 只看最终结论不显示中间过程。

> 这条路**必须有大模型凭据**。不想配的话，前两种方式都不需要 Key。
### 方式二：命令行（适合脚本化和进 CI）

```bash
# 用内置演示文档，不用准备任何数据
java -jar ragsight-cli/target/ragsight-cli.jar diagnose

# 诊断自己的文档
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
--rerank                改成对比"加不加重排"
-o report.md            把报告写到文件
```

### 换成真实的向量模型

默认用的是**离线哈希向量化**：不联网、不用 Key、结果可复现，但**只认字面重合**——
问"账单什么时候出"而文档写"账单生成时间"，它一个词都对不上。所以它的绝对分数只能用来
验证流程，不能代表真实模型的效果。想要有参考价值的数字，换上真实模型：

```bash
# Key 放环境变量，不会写进报告也不会落盘
$env:RAGSIGHT_API_KEY = "你的key"          # Windows PowerShell
export RAGSIGHT_API_KEY=你的key             # Linux / macOS

java -jar ragsight-cli/target/ragsight-cli.jar diagnose \
     --corpus examples/corpus \
     --eval examples/eval.txt \
     --embedding-model text-embedding-v4
```

默认连的是阿里 DashScope（百炼）的 OpenAI 兼容接口。换别家只改地址和模型名：

```bash
--embedding-base-url https://api.siliconflow.cn/v1
--embedding-model    BAAI/bge-m3
```

维度不用你填——程序连上时会拿一句话试一下，直接问出来。

#### 为什么这一步不能省：实测差距大到结论会翻转

拿同一份语料（`examples/corpus`，4 篇文档、10 个用例）分别用两种向量化跑，
只看**向量召回**的 MRR：

| 切分方案 | 离线哈希 | 真实 `text-embedding-v4` | 差距 |
|---|---|---|---|
| 递归切分 90 字 | 0.688 | **1.000** | **+0.312** |
| 递归切分 200 字 | 0.838 | 0.938 | +0.100 |
| 固定窗口 90 字 | 0.708 | 0.875 | +0.167 |

对照组是**关键词召回**（它不用向量，所以两次应该完全一样）：

| 切分方案 | 离线哈希 | 真实 `text-embedding-v4` | 差距 |
|---|---|---|---|
| 递归切分 90 字 | 0.823 | 0.823 | **0** |
| 递归切分 200 字 | 0.938 | 0.938 | **0** |
| 固定窗口 90 字 | 0.823 | 0.823 | **0** |

关键词召回三次一个字都没变——这证明上面的差异确实来自向量模型，
不是随机波动。

**要命的地方在于结论会反过来：**

- 用离线哈希看：关键词（0.823）比向量（0.688）**高 0.135** → 会建议你改用关键词召回
- 用真实模型看：向量（1.000）比关键词（0.823）**高 0.177** → 应该保持向量召回

也就是说，如果照着离线哈希的结论去改生产配置，**等于把好的换成差的**。

所以：**离线哈希只能用来验证流程跑得通，不要拿它的结论指导决策。**
想知道"我的 RAG 到底该用哪种召回"，必须接真实向量模型。

---

## 现在做到哪了

> 两层都做完了。能诊断、能对比、能让 Agent 自己探索，也在真实向量模型上实测过。
> 下面是如实的状态，包括还没做的。

### 第一层：算指标、跑对比

| 部分 | 状态 |
|---|---|
| 读文档 | 支持 Markdown / 纯文本目录 |
| 文档切分 | 固定窗口、递归字符（两种，计划五种） |
| 召回 | 向量召回、关键词召回（BM25）、两者融合（RRF） |
| 重排 | 字面重合重排（当基线用，不是最终方案） |
| 指标 | Recall@k、MRR、nDCG（都是手算验证过的） |
| 诊断报告 | Markdown，含逐条用例的命中位置 |
| 对比维度 | 切分方式、召回方式、要不要重排 |
| 真实向量模型 | ✅ **已实测**（DashScope `text-embedding-v4`），与离线哈希的差距已写进文档 |
| 架构测试 | **12 条**规则守住依赖方向，另有自检测试确认门禁真的会拦人 |

### 第二层：让 Agent 自己决定试什么

| 部分 | 状态 |
|---|---|
| 诊断 Agent（AgentScope） | ✅ **已真实跑通**，7 步 / 16 次实验 / 约 16 秒 |
| 单变量对比工具 | ✅ 4 个工具，一次只改一个参数 |
| 决策过程可视化 | ✅ 命令行实时输出 + 网页 SSE 推送 |

### 三个入口

| 入口 | 状态 |
|---|---|
| 命令行 | `diagnose`，支持内置演示文档和外部目录 |
| 网页界面 | 热力图 + 结论 + 明细表，可实时看 Agent 思考过程 |
| Agent 命令行 | `ragsight-agent`，独立 jar（不跑大模型的人不需要它） |

### 还没做的

| 部分 | 说明 |
|---|---|
| 更多切分策略 | 现在 2 种，计划 5 种（加语义切分、按标题切等） |
| 向量数据库 | 现在是内存暴力算。几万片段以内够用，更大要换 pgvector |
| 大语料压测 | 只在 4 篇文档的小语料上验证过，片段数远超候选池的情况没测 |
| HTML 报告导出 | 网页有了，静态 HTML 主要用于"把报告发给别人" |
| 网页 Agent 入口的浏览器实测 | 只验到 SSE 接口层面，前端代码没有自动化测试 |

详细的进度、下一步和踩过的坑都在 [PROGRESS.md](PROGRESS.md)，那份文件是跨会话交接用的，写得很具体。

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
| [docs/learn/](docs/learn/) | **学习文档**：想搞懂 RAG 和 Agent，从这套开始 |
| [docs/design/](docs/design/) | **架构说明**：分层、模块、设计模式、二次开发指南 |
| [docs/adr/](docs/adr/) | 每个重要决定的原因，以及被否掉的方案 |
| [docs/setup/environment.md](docs/setup/environment.md) | 环境要求和常见问题 |

### 学习文档（想搞懂 RAG / Agent，看这套）

每一篇都有能立刻跑的命令，不用先读完再动手。

| 文档 | 内容 |
|---|---|
| [README](docs/learn/README.md) | 学习路径：什么顺序看、每篇什么结构 |
| [01-跑起来看结果](docs/learn/01-跑起来看结果.md) | 报告七块各在回答什么问题 |
| [02-RAG在解决什么问题](docs/learn/02-RAG在解决什么问题.md) | 三个阶段，为什么只诊断检索 |
| [03-切分](docs/learn/03-切分.md) | 切太碎和切太大的代价，为什么"片段大指标就好"是假象 |
| [04-召回](docs/learn/04-召回.md) | 向量和关键词各擅长什么，为什么不总用融合 |
| [05-指标](docs/learn/05-指标.md) | 手算 Recall / MRR / nDCG，和工具输出对上 |
| [06-评测集](docs/learn/06-评测集.md) | 期望文字怎么写、负样本为什么必须有 |
| [07-AgentScope是什么](docs/learn/07-AgentScope是什么.md) | ReAct 循环怎么转，框架替你省了什么 |
| [08-让Agent自己决定试什么](docs/learn/08-让Agent自己决定试什么.md) | 单变量对比为什么是关键 |

### 架构说明（想读代码或改代码，看这套）

| 文档 | 内容 |
|---|---|
| [00-一次诊断的完整流程](docs/design/00-一次诊断的完整流程.md) | 从敲命令到出报告，中间经过了什么 |
| [01-分层与依赖方向](docs/design/01-分层与依赖方向.md) | 五个模块的职责，为什么依赖只能单向 |
| [02-核心的三个模块](docs/design/02-核心的三个模块.md) | 领域模型为什么拆成三块 |
| [03-设计模式清单](docs/design/03-设计模式清单.md) | 用了哪些模式、解决什么问题、不用会怎样 |
| [04-二次开发指南](docs/design/04-二次开发指南.md) | 加功能时改哪里、按什么顺序、容易漏什么 |
| [05-代码地图](docs/design/05-代码地图.md) | 按"我想干什么"查文件 |
| [06-术语表](docs/design/06-术语表.md) | RAG 和本项目的词，用大白话解释 |

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
