# PROGRESS.md — 进度与交接日志

> **用途**：跨会话的交接文件。新会话读完 `AGENTS.md` 后立刻读本文件。
> **纪律**：每个「✅ 已完成」必须附带**实际执行的命令 + 实际输出**，不接受"应该能跑"。

- **最后更新**：2026-10-14
- **当前阶段**：P2 检索管道（进行中）
- **远程仓库**：https://github.com/qiyec/ragsight （public）
- **最近提交**：`636ad28` chore: 移除误提交的 commitmsg.txt

---

## ✅ 已完成

### P0 环境与骨架

**验证命令与结果**：
```
mvnw.cmd verify   ->  BUILD SUCCESS
                     ragsight :: domain    Tests run: 49, Failures: 0, Errors: 0
                     ragsight :: architecture-tests  Tests run: 5, Failures: 0
java -jar ragsight-cli/target/ragsight-cli.jar --version
                  ->  ragsight 0.1.0-SNAPSHOT
```

| 交付物 | 说明 |
|---|---|
| Maven 多模块骨架 | domain / application / infrastructure / cli / architecture-tests |
| Maven Wrapper | 锁定 3.9.9，`distributionUrl` 走阿里云 |
| Enforcer | 强制 JDK 17+ / Maven 3.8+，不满足时中文报错 |
| 可选 Toolchains profile | ⚠️ **已移除**（导致 CI 失败，详见下方"已修复的缺陷"） |
| `.gitattributes` | `mvnw` 强制 LF（否则 Linux 报 bad interpreter） |
| README / LICENSE / CI | Apache-2.0；GitHub Actions 矩阵构建（JDK 17 + 21） |
| **已推送到 GitHub** | `gh repo create qiyec/ragsight --public` 成功 |
| AGENTS.md | 新会话第一读物：定位、铁律、约定、上下文协议 |
| ADR 001/002/003 | 两层架构 / domain 零依赖 / 为何不做框架 |
| docs/setup/environment.md | 环境实测事实与排查清单 |
| CLI 骨架 | picocli 入口，版本号从 manifest 读取 |

### P1 领域层三上下文

#### ① knowledge（知识库）

| 类 | 职责 |
|---|---|
| `DocumentId` / `ChunkId` | 值对象；`ChunkId` 用「文档#序号」复合标识，保证报告可读可复现 |
| `Document` | 聚合：承载 `toChunks()` 不变式（序号连续、剔除空白片段） |
| `Chunk` | 检索最小单位，含可选 heading 元数据 |
| `ChunkingStrategy` | 端口；要求**无状态纯函数**（可复现性） |
| `FixedWindowChunkingStrategy` | 基线策略：window + overlap |
| `RecursiveCharacterChunkingStrategy` | 按分隔符优先级递归，中文标点优先，含硬切兜底与重叠注入 |

#### ② retrieval（检索）

| 类 | 职责 |
|---|---|
| `ScoredChunk` | 值对象：片段 + 分数 + 排名 + 来源阶段 |
| `RetrievalSource` | 枚举：VECTOR / KEYWORD / FUSION / RERANK / MISSED，带中文标签 |
| `Retriever` | 端口：query + topK → 候选列表 |
| `Reranker` | 端口：候选重排 |
| `ReciprocalRankFusion` | **RRF 多路融合**，默认 k=60，支持来源权重 |

#### ③ evaluation（评估）

| 类 | 职责 |
|---|---|
| `GoldenCase` | 标注用例；`isNegative()` 标识负样本（期望集合为空） |
| `EvaluationSet` | **聚合根**，维护"用例 id 唯一"不变式 |
| `RetrievedEvidence` | 只保留「片段标识 + 排名」，**刻意丢弃分值**（防止拿不同量纲的分数算指标） |
| `Metric` | 指标端口；`scoreAll()` 默认跳过 NaN（负样本在排序类指标中不计入） |
| `RecallAtK` | 召回率，负样本按定义记满分 1.0 |
| `MRR` | 平均倒数排名，负样本返回 NaN 不参与平均 |

#### ④ architecture-tests（架构门禁）

用 ArchUnit 把 `AGENTS.md` 的铁律变成构建门禁，共 5 条规则：

| 规则 | 守护的铁律 |
|---|---|
| `domain_不应依赖任何第三方库` | R1（仅允许 `java..` 与 domain 自身） |
| `domain_不应依赖常见便利库` | R1（点名校验 Jackson / Lombok / Guava / Spring / SLF4J / AgentScope） |
| `application_只能依赖_domain` | R2 |
| `domain_不应使用随机数` | R5（可复现性） |
| `domain_不应依赖基础设施` | R3（六边形依赖方向） |

**测试数量**：domain 49 + architecture 5 = 54 个，全部通过。

### P2 检索管道（部分完成）

**验证命令与结果**：
```
mvnw.cmd -pl ragsight-infrastructure -am test
  ->  ragsight :: domain          Tests run: 49, Failures: 0
      ragsight :: infrastructure  Tests run: 19, Failures: 0
```

| 交付物 | 说明 |
|---|---|
| `EmbeddingModel`（domain 端口） | 批量接口 + 维度契约；批量是为了避免逐条调用的固定开销 |
| `VectorIndex`（domain 端口） | rebuild/search；接收向量而非文本（否则"用哪个模型"被埋进索引实现） |
| `DeterministicHashEmbedding` | 离线确定性向量（hashing trick）。**CI 无需 API Key 的关键** |
| `InMemoryVectorIndex` | 暴力余弦检索；零外部依赖启动 |
| `VectorRetriever` | 组合 embedding + index；含 `indexed()` 一步构建入口 |

**实测向量相似度**（用于固化能力边界，见测试注释）：

| 文本对 | 余弦 | 含义 |
|---|---|---|
| 计费规则 / 收费规则 | 0.7500 | 共享「费规则」3 字 |
| 计费 / 收费 | **0.5000** | 语义相同但零共同字符——**证明它没有语义泛化能力** |
| 计费规则 / 天气晴朗 | 0.0000 | 零重叠时为精确 0 |
| 计费规则如何计算 / 计费规则 | 0.7906 | 字面高度重合 |

> 这张表是"离线实现不能代表真实 embedding 效果"的可执行证据。
> 换用真实模型时，`semantically_similar_but_lexically_different_is_not_guaranteed`
> 这条测试会失败——届时应当**重新审视**它，而不是当作"实现变好了"删掉。

---

## 🔄 进行中

P2 尚未完成的部分：

1. **`InMemoryVectorIndex` / `VectorRetriever` 尚无单元测试** ← 最优先
2. **`BM25Retriever` 未实现** —— 关键词召回，混合检索的另一半
3. **`LexicalOverlapReranker` 未实现** —— 重排阶段
4. **`nDCG` 未实现** —— 需带手算验证测试
5. **`DiagnosticReport` 聚合根 + `CaseOutcome` 未实现**
6. **`ragsight-application` 仍为空** —— 用例编排层

---

## ⏭️ 下一步（精确到文件）

1. `ragsight-infrastructure`：补 `InMemoryVectorIndexTest`、`VectorRetrieverTest`
   （重点：维度不匹配报错、空语料、同分排序稳定性、topK 截断）
2. `ragsight-domain`：`Bm25Retriever` 需要词频统计——先确认它属于 domain 还是
   infrastructure（**决策点**：BM25 是纯算法，倾向于放 domain；但它需要分词，
   而分词实现可能依赖外部库）
3. `ragsight-domain`：补 `nDCG` + 手算测试
4. `ragsight-application`：`RunDiagnosisUseCase`
   —— **同时**把 `archunit.properties` 的 `failOnEmptyShould` 改回 `true`
5. `ragsight-domain`：`DiagnosticReport` 聚合根 + `CaseOutcome`
6. 完整验证 + 提交 + 推送（**确认 CI 双 JDK 全绿**）

---

## ❓ 待决策

| # | 事项 | 状态 |
|---|---|---|
| 1 | 仓库名 `ragsight` | ✅ 已确认并创建：https://github.com/qiyec/ragsight |
| 2 | 推送到 GitHub | ✅ 已完成 |
| 3 | 评测集用哪份文档构建（需公开可复现的语料） | ⏳ P2 后期决定 |
| 4 | BM25 放 domain 还是 infrastructure（分词依赖问题） | ⏳ 下一步决策 |

---

## 🧪 已验证的事实（命令 + 实际输出）

| # | 验证内容 | 命令 | 实际结果 |
|---|---|---|---|
| 1 | AgentScope 构件坐标 | `gh api .../agentscope-core/pom.xml` | `groupId=io.agentscope` |
| 2 | AgentScope 已发 Maven Central | `maven-metadata.xml` | `agentscope-bom` 等 `latest=2.1.0-BETA1`，稳定版 `2.0.4` |
| 3 | **AgentScope 自身基线是 Java 17** | `gh api .../agentscope-java/main/pom.xml` | `<java.version>17</java.version>`，`maven.compiler.release=17`<br>→ **结论：本项目统一 JDK 17，Agent 层不需要 21** |
| 4 | Maven 3.8.8 可跑在 JDK 21 | `JAVA_HOME=<jdk21>; mvn -v` | `Java version: 21.0.7` |
| 5 | 全局 settings.xml 启用阿里云镜像 | Maven 构建日志 | 依赖显示 `Downloaded from aliyun-central` |
| 6 | 本地仓库非默认位置 | 读 `conf/settings.xml` | `D:\develop\environment\Maven\repo` |
| 7 | Scoop 未管理 Java/Maven | `scoop list` | 仅 `7zip` / `git` / `neofetch` |
| 8 | 无 SDKMAN / mise / asdf | `Get-Command sdk` 等 | 全部 not found |
| 9 | `mvnw` 换行符正确 | `git ls-files --eol mvnw` | `i/lf w/lf attr/text=auto eol=lf` |
| 10 | 全量构建 | `mvnw.cmd verify` | `BUILD SUCCESS`，54 个测试全通过 |
| 11 | CLI 可执行 | `java -jar ... --version` | `ragsight 0.1.0-SNAPSHOT` |
| 12 | CLI 中文输出正常 | 加 `-Dstdout.encoding=UTF-8` | 中文正常显示（不加则控制台乱码） |

---

## 📌 已知问题 / 技术债

| # | 问题 | 影响 | 计划 |
|---|---|---|---|
| 1 | `archunit.properties` 设了 `failOnEmptyShould=false` | 架构规则若被写空将不再报警，门禁可能静默失效 | **P2 有了 application 真实类后改回 `true`** |
| 2 | 缺 nDCG 指标 | 诊断报告少一个排序质量维度 | P2 |
| 3 | `Retriever` / `Reranker` 无实现 | 尚不能真正跑检索 | P2 |
| 4 | CLI 只有骨架，无功能子命令 | `rag diagnose` 尚未存在 | P3 |
| 5 | 无 CI 配置 | 推送后无法自动验证 | P2 加 GitHub Actions |
| 6 | 无 LICENSE 文件 | 开源仓库必需 | 推送前补（建议 Apache-2.0） |
| 7 | 无 README.md | 仓库第一眼印象所必需 | 推送前补 |

---

## 🐛 已修复的缺陷（记录以免重复犯错）

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| 1 | Maven wrapper 生成无效 URL（`apache-maven/3/apache-maven-3-bin.zip`） | PowerShell 把 `-Dmaven=3.9.9` 按点号拆成两个参数，Maven 报 `Unknown lifecycle phase ".9.9"`，插件回退默认值 | 直接写 `maven-wrapper.properties`，锁定 3.9.9 |
| 2 | `RecursiveCharacterChunkingStrategy.mergeWithOverlap()` 顺序错乱 | 超长片段分支 `continue` 前未 flush buffer，可能把超长片段插到前面片段之前 | 先 flush buffer 再处理超长片段 |
| 3 | RRF 融合分数不累加 | 误用 `ScoredChunk` 作聚合键，但其 `record equals` 含 `rank`，同一片段在不同路被视为不同条目 | 改为按 `ChunkId` 聚合 |
| 4 | 测试断言"片段拼接 == 原文"误报 | 段落间 `\n\n` 不属于任何片段（片段会 strip），该断言本身不成立 | 改为与"非空白内容"比较 |
| 5 | POM 无法解析 | XML 注释中写了 `--version`，`--` 在 XML 注释中非法 | 改写注释文本 |
| 6 | 架构测试对空包失败 | `ragsight-application` 尚无类，规则匹配不到 | 加 `archunit.properties`（见技术债 #1） |
| 7 | **CI 的 JDK 21 job 失败**，JDK 17 通过 | 曾加的 Toolchains profile 用"`~/.m2/toolchains.xml` 存在"作激活条件；GitHub runner 自带该文件但其中没有 JDK 17 → `Cannot find matching toolchain definitions` | **彻底移除 Toolchains profile**，回归 Enforcer 显式校验。教训：隐式文件存在性做构建开关是脆弱的，会造成"本地通过、CI 失败" |
| 8 | 离线 embedding 的能力边界测试断言写错 | 我以为「计费规则」与「收费规则」相似度低，实际共享 3 个字符，实测 0.75——**实现是对的，断言是错的** | 先加探针测量真实值，再按实测值写断言；并补一条「计费 / 收费 = 0.5」精确暴露"无语义泛化能力" |

---

## 🔗 相关文档

| 文件 | 内容 |
|---|---|
| `AGENTS.md` | 项目定位、铁律、编码约定、上下文管理协议 |
| `docs/adr/001-two-layer-architecture.md` | 为什么分两层 |
| `docs/adr/002-domain-zero-dependencies.md` | 为什么 domain 零依赖 |
| `docs/adr/003-why-diagnostic-tool-not-framework.md` | 为什么不造框架（含官方能力核查证据） |
| `docs/setup/environment.md` | 环境要求、实测状态、排查清单、为什么不用 Toolchains |
