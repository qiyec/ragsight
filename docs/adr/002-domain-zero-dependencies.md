# ADR-002：为什么 domain 层禁止任何第三方依赖

- **状态**：已接受
- **日期**：2026-10-13
- **决策者**：项目发起人

---

## 背景

Java 项目里，领域模型很容易被"顺手"加上依赖：

- 用 Lombok 少写 getter
- 用 Jackson 注解做序列化
- 用 Guava 的 `ImmutableList`
- 用 Spring 的 `@Service` 做依赖注入
- 用向量库 SDK 的数据类型做检索结果

每一个单独看都"只是个小依赖"，累积起来的后果是：
**领域逻辑无法脱离运行环境被理解和测试**。

---

## 决策

`ragsight-domain` 模块的 `pom.xml` **只允许测试依赖**（JUnit / AssertJ）。
所有第三方运行时依赖一律禁止。

外部能力必须以**接口（端口）**形式出现在 domain 中，实现在 `ragsight-infrastructure`。

### 具体约束

| 禁止 | 替代方案 |
|---|---|
| Lombok | `record`（JDK 17 原生） |
| Jackson / Gson 注解 | 序列化逻辑放 infrastructure 的 DTO 中 |
| Guava 集合 | JDK 集合 API（`List.of` / `Map.copyOf`） |
| Spring 注解 | 在 infrastructure 或 cli 装配 |
| 向量库 SDK 类型 | domain 自定义 `ScoredChunk` 等值对象 |
| HTTP / JSON 客户端 | 定义为端口接口，实现放 infrastructure |

### 强制手段

由 `ragsight-architecture-tests` 模块用 **ArchUnit** 自动校验（P1 引入）：

```java
@ArchTest
static final ArchRule domain_不应依赖任何第三方库 =
    classes().that().resideInAPackage("cn.qiyec.ragsight.domain..")
        .should().onlyDependOnClassesThat()
        .resideInAnyPackage("java..", "cn.qiyec.ragsight.domain..");
```

**这条规则会在 `mvn verify` 中执行，违反即构建失败。**

---

## 理由

### 1. 可测试性（最主要理由）

领域层是 RAG 算法和指标计算的核心。这部分逻辑必须能够在：
- 没有网络
- 没有数据库
- 没有 API Key
- 没有向量库

的环境下**完整、快速地测试**。

一旦 domain 依赖了某个向量库 SDK，测试就必须启动那个库；一旦依赖了 HTTP 客户端，
测试就要 mock 网络。测试成本上升会直接导致测试被跳过，最终导致核心算法无人验证。

### 2. 算法正确性可以被独立证明

指标实现（`Recall@k` / `MRR` / `nDCG`）必须有**手算验证**的测试。
这只有在纯函数式、无外部干扰的前提下才容易做到。

### 3. 架构意图显式化

"什么属于核心领域，什么只是实现细节"——依赖方向就是最清晰的表达。
domain 不依赖任何东西，意味着它定义了系统的**稳定内核**；外部一切皆为可替换插件。

### 4. 换实现零成本

换向量库、换 embedding 模型、换文档解析器——都只影响 infrastructure。
这是六边形架构（端口-适配器）的实际收益，不是纸面概念。

---

## 被否决的方案

### 方案 A：允许 Lombok

- ❌ 编译期字节码增强，IDE / 构建工具敏感
- ❌ JDK 17 的 `record` 已覆盖主要场景（值对象）
- 📌 若确有需要（如 Builder 样板），需另开 ADR 讨论

### 方案 B：允许 Jackson 注解

- ❌ 序列化格式属于基础设施关注点，不该污染领域模型
- ✅ 替代：infrastructure 层定义独立的 DTO 做映射

### 方案 C：用 Maven Enforcer 的 `bannedDependencies` 强制

- ⚠️ 能禁止直接依赖，但**无法检测对传递依赖的间接使用**
- ⚠️ 且无法表达"只允许 java.. 和自身包"这类包级规则
- ✅ 最终选择 ArchUnit：能做包级依赖断言，表达力更强，测试即是文档

---

## 影响

### 正面

- 核心算法零依赖、快速测试、易于验证正确性
- 架构边界由自动化测试守护，不靠人自觉
- 端口-适配器结构真实落地，换实现成本极低

### 负面 / 代价

- **要多写一些样板**：没有 Lombok，值对象要么用 `record`，要么手写
  → 缓解：值对象一律用 `record`，已足够
- **需要在 infrastructure 写 DTO 映射**
  → 缓解：这是防腐层的正常成本，且隔离了外部格式变化
- **引入 ArchUnit 作为测试依赖**（在 architecture-tests 模块，不在 domain）

---

## 相关

- ADR-001：两层架构
- `AGENTS.md` 第 3 节（铁律 R1 / R3）
