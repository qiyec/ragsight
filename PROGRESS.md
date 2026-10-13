# PROGRESS.md — 进度与交接日志

> **用途**：跨会话的交接文件。新会话读完 `AGENTS.md` 后立刻读本文件。
> **纪律**：每个「✅ 已完成」必须附带**实际执行的命令 + 实际输出**，不接受"应该能跑"。

- **最后更新**：2026-10-13
- **当前阶段**：P0 环境固化（进行中）

---

## ✅ 已完成

### 1. 环境勘察（含两处对既有判断的纠正）

**命令**：`gh api repos/agentscope-ai/agentscope-java/contents/...`、`mvn -v`、`scoop list`、`uv python list` 等

**关键事实**（详见 `docs/setup/environment.md`）：

| 事实 | 说明 |
|---|---|
| JDK | 17.0.14 在 PATH 中；`D:\develop\environment\JDK\` 下另有 8/11/21 |
| `JAVA_HOME` | **未设置**，但 Maven 会回退 PATH，故构建正常 |
| Maven 版本 | 3.8.8（本地）；wrapper 锁定 3.9.9 |
| 镜像 | ✅ **已配置阿里云**，在 `Maven安装目录/conf/settings.xml`（**不在** `~/.m2`） |
| 本地仓库 | `D:\develop\environment\Maven\repo`（非默认位置） |
| 版本管理器 | **无 SDKMAN**；有 Scoop（仅 git/7zip/neofetch）、fnm、uv |
| Docker | 29.5.2 运行中 |

**纠正的两个错误判断**（记录以免重复犯错）：
1. ❌ 曾判断"`JAVA_HOME` 空 → 构建会失败" → ✅ 实际 Maven 回退 PATH，正常
2. ❌ 曾判断"无 settings.xml，直连 Central" → ✅ 实际全局 settings.xml 已配阿里云镜像

### 2. AgentScope 官方能力核查（决定项目方向的关键依据）

**命令**：`gh api repos/agentscope-ai/agentscope-java/git/trees/main?recursive=1`

**结论**：官方已有 `agentscope-spring-boot-starter`、`agentscope-admin-spring-boot-starter`（含审计、会话管理、子 Agent 任务控制器）、`agentscope-extensions-rag`（5 种 RAG 集成）。
**因此放弃"脚手架"方向，改做 RAG 诊断工具**（详见 `docs/adr/003`）。

### 3. 项目骨架建立

```
ragsight/
├── pom.xml                    父 POM（Enforcer 强制 JDK17+ / Maven3.8+）
├── mvnw / mvnw.cmd / .mvn/    Maven Wrapper（锁定 3.9.9，走阿里云）
├── AGENTS.md                  新会话第一读物
├── PROGRESS.md                本文件
├── docs/
│   ├── adr/001,002,003        架构决策记录
│   └── setup/environment.md   环境事实与排查清单
├── ragsight-domain            纯 Java 领域层（零第三方运行时依赖）
├── ragsight-application       用例编排
├── ragsight-infrastructure    适配器
└── ragsight-cli               picocli 入口
```

### 4. 领域层（knowledge 上下文）首批实现

| 类 | 职责 |
|---|---|
| `DocumentId` / `ChunkId` | 值对象；`ChunkId` 用「文档#序号」复合标识，保证报告可读可复现 |
| `Document` | 聚合：承载 `toChunks()` 不变式（序号连续、剔除空白） |
| `Chunk` | 检索最小单位，含 heading 可选元数据 |
| `ChunkingStrategy` | 端口接口，要求**无状态纯函数**（R5 可复现性） |
| `FixedWindowChunkingStrategy` | 基线策略，window + overlap |
| `RecursiveCharacterChunkingStrategy` | 按分隔符优先级递归，中文标点优先 |

**测试**：2 个测试类，覆盖参数校验、边界覆盖、重叠正确性、序号连续性、**确定性**（可复现性）。

---

## 🔄 进行中

**P0 收尾**：验证 `mvnw.cmd verify` 全绿 → git init → 首次提交 → 推送 GitHub。

---

## ⏭️ 下一步（精确到文件）

1. **确认 P0 构建通过**：`mvnw.cmd verify` 全绿（含 2 个测试类）
2. **修复已知设计粗糙点**：
   - `RecursiveCharacterChunkingStrategy.mergeWithOverlap()` 中超长片段的处理分支会**跳过 buffer 累积**，
     导致片段顺序被打乱的可能性。需重写为"先 flush buffer 再处理超长片段"。
   - 补一个测试：验证超长片段场景下的**片段顺序与原文一致**
3. **`git init` + 首次提交 + 推送**（`gh repo create qiyec/ragsight --public --source=. --push`）
4. **进入 P1**：补齐 domain 三上下文
   - `retrieval/`：`ScoredChunk`、`Retriever`、`Reranker`、`FusionStrategy` 端口
   - `evaluation/`：`GoldenCase`、`EvaluationSet`、`Metric`、`RecallAtK`、`MRR`、`NDCG`、`DiagnosticReport`
   - 新增 `ragsight-architecture-tests` 模块，用 **ArchUnit** 强制 R1（domain 零第三方依赖）
5. **补 ADR-005**：元评测设计（如何证明诊断工具本身正确）

---

## ❓ 待决策

| # | 事项 | 备选方案 | 状态 |
|---|---|---|---|
| 1 | 仓库名 `ragsight` | 已建议，**待用户最终确认** | ⏳ |
| 2 | Java 版本管理方案 | A. 什么都不装用 wrapper（推荐）<br>B. 用 Scoop 统一管<br>C. 装 SDKMAN/mise | ⏳ 用户取消了提问，待重提 |
| 3 | 是否需要 pgvector 对比实验 | 需要 Docker（已具备） | 未到该阶段 |

---

## 🧪 已验证的事实（命令 + 实际输出）

| # | 验证内容 | 命令 | 实际结果 |
|---|---|---|---|
| 1 | AgentScope 构件坐标正确 | `gh api .../agentscope-core/pom.xml` | `groupId=io.agentscope`，`artifactId=agentscope-core` |
| 2 | AgentScope 已发布 Maven Central | `Invoke-WebRequest .../maven-metadata.xml` | `agentscope-bom` / `agentscope-harness` / `agentscope-spring-boot-starter` / `agentscope-extensions-rag` 全部 `latest=2.1.0-BETA1`；稳定版 `2.0.4` |
| 3 | Maven 3.9.9 分发地址可用 | `Invoke-WebRequest -Method Head` | 阿里云与官方源均返回 `200` |
| 4 | 全局 settings.xml 启用阿里云镜像 | 构建日志 | 所有依赖显示 `Downloaded from aliyun-central` |
| 5 | Scoop 未管理 Java/Maven | `scoop list` | 仅 `7zip` / `git` / `neofetch` |
| 6 | Maven wrapper 生成 | `mvn -N wrapper:wrapper` | ⚠️ 首次失败：`-Dmaven=3.9.9` 被 PowerShell 按点拆参 → `Unknown lifecycle phase ".9.9"`，产出无效 URL。已手工修正为 3.9.9 + 阿里云地址 |
| 7 | domain 层首次编译 | `mvnw.cmd -B verify` | ⚠️ 失败：`Document.java` 引用了尚未创建的 `Chunk`（时序问题，非代码缺陷）。补齐后重验 |

---

## 📌 已知问题 / 技术债

| # | 问题 | 影响 | 计划 |
|---|---|---|---|
| 1 | `mergeWithOverlap()` 超长片段分支可能打乱顺序 | 影响片段 ordinal 与原文一致性 | P0 收尾修复（见"下一步"第 2 条） |
| 2 | Maven 输出中文乱码 | 仅影响可读性，不影响构建 | 已用 `MAVEN_OPTS=-Dfile.encoding=UTF-8` 缓解；待确认是否需写入 `.mvn/jvm.config` |
| 3 | 缺 ArchUnit 架构测试 | R1 铁律尚未自动守护 | P1 引入 |
| 4 | POM 的 domain 模块 description 提到 `ragsight-architecture-tests`，但该模块尚未创建 | 文档与实现不一致 | P1 创建，或先改描述 |

---

## 🔗 相关文档

| 文件 | 内容 |
|---|---|
| `AGENTS.md` | 项目定位、铁律、编码约定、上下文管理协议 |
| `docs/adr/001-two-layer-architecture.md` | 为什么分两层 |
| `docs/adr/002-domain-zero-dependencies.md` | 为什么 domain 零依赖 |
| `docs/adr/003-why-diagnostic-tool-not-framework.md` | 为什么不造框架（含官方能力核查证据） |
| `docs/setup/environment.md` | 环境要求、作者机器实测状态、排查清单 |
