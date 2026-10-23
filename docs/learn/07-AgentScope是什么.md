# 07 AgentScope 是什么

## 这篇解决什么问题

**"我已经会调大模型 API 了，为什么还要学 Agent 框架？"**

这篇讲清框架替你做了什么。学完你能判断：什么场景该上框架，什么场景裸调 API 就够。

> 这篇会用一个真实的运行日志来讲，那是我用这个项目的诊断 Agent 跑出来的。
> 想复现需要 DashScope 的 Key（见文末）。但**核心内容不需要跑也能看懂**。

---

## 先跑一下

不用 Key 也能看到"框架提供了什么"：

```bash
java -jar ragsight-agent/target/ragsight-agent-exec.jar --help
java -jar ragsight-agent/target/ragsight-agent-exec.jar diagnose --list
```

第二个命令会列出可用的语料，不需要任何凭据。

**真正跑一次**需要 Key：

```bash
$env:DASHSCOPE_API_KEY = "你的key"
java -jar ragsight-agent/target/ragsight-agent-exec.jar diagnose
```

---

## 看到了什么

这是真实跑出来的日志（我删掉了中间输出，只留骨架）：

```
[调用] run_baseline(chunk_size=120)
       ↓ "基准结果（语料：示例：corpus，召回条数 5）：…"
[调用] compare_one_change(new_value=true, change=rerank)
       ↓ "只改了一处：重排 关 → 开…"
[调用] compare_one_change(new_value=90, change=chunk_size)
       ↓ "只改了一处：切分窗口 120 字 → 90 字…"
[调用] compare_one_change(new_value=200, change=chunk_size)
       ↓ "只改了一处：切分窗口 120 字 → 200 字…"
[调用] compare_one_change(new_value=300, change=chunk_size)
       ↓ "只改了一处：切分窗口 200 字 → 300 字…"
[调用] compare_one_change(new_value=60, change=chunk_size)
       ↓ "只改了一处：切分窗口 200 字 → 60 字…"
[调用] finish()
       ↓ "========== 诊断结论 ==========…"
```

**7 步。** 每一步都是：模型决定调哪个工具 → 工具执行 → 结果回给模型 → 模型决定下一步。

这就是 **ReAct 循环**（Reasoning + Acting）。

---

## 为什么是这样

### 没有框架的时候，你得自己写这个循环

假设你直接调 LLM API（不走框架），想实现上面那个流程。

你要做的事：

```java
// 伪代码
while (true) {
    response = 调LLM(对话历史, 工具列表);

    if (response 里没有工具调用) {
        break;   // 模型认为可以结束了
    }

    for (每个工具调用) {
        参数 = 解析JSON(response 的工具调用参数);
        结果 = 执行对应的方法(参数);          // 你要自己分发
        对话历史.add(工具结果);
    }

    if (轮数 > 上限) break;   // 防止死循环
}
```

看起来不长，但坑在细节里：

| 你要处理的事 | 麻烦在哪 |
|---|---|
| 把工具描述成 LLM 能看懂的 schema | 每家有细微差别，参数类型、必填项、描述怎么写 |
| 解析模型返回的工具调用 | JSON 格式、参数名、多个工具并行调用 |
| 参数校验 | 模型给错类型怎么办？给错参数名怎么办？ |
| 把结果塞回对话历史 | 格式必须和模型期望的一致，否则下一轮它就懵了 |
| 循环控制 | 什么时候停？轮数上限？工具报错要不要重试？ |
| 多轮上下文管理 | 历史越来越长，什么时候截断 |

**这些东西和你的业务一点关系都没有，但每一样写错都会"看起来正常工作、其实行为不对"。**

### 框架替你做的就是这些

以这个项目为例，你只需要两样东西：

**一、工具：一个带注解的普通 Java 方法**

```java
@Tool(name = "run_baseline",
      description = "跑一组基准实验，作为后面所有比较的参照。这是第一步，必须先调用。")
public String runBaseline(
        @ToolParam(name = "chunk_size", description = "切分窗口大小（字符）")
        int chunkSize) {
    // 你的业务逻辑
}
```

**没有实现任何接口，没有继承任何类。** 就是个普通方法。

框架会：
- 从注解和参数类型**推导出 JSON schema**（`chunk_size` 是 integer、必填）
- 把这个 schema 发给模型
- 模型要调时，反射调用你这个方法
- 把返回值包装成模型能读的格式塞回对话

**二、组装：告诉框架用哪个模型、哪些工具**

```java
ReActAgent.builder()
        .name("ragsight-diagnoser")
        .sysPrompt(系统提示词)
        .model("dashscope:qwen-plus")
        .toolkit(toolkit)          // 里面注册了上面那些工具
        .maxIters(12)              // 循环上限
        .build();
```

然后就 `agent.call("开始诊断")`，循环自己跑。

### 为什么这个项目用 AgentScope

有三个具体原因，不是"因为它是潮流"：

**1. 它把"循环 + 工具"标准化了**

上面那些坑（schema 推导、参数校验、结果回传）它都处理了。
特别值得一提：**参数校验失败时，它会把"缺少 xxx，请修正后重试"作为工具结果回给模型**，
模型有机会自己改对。这个不好自己实现。

**2. 观察点齐全**

它有 Hook 机制，能在"调工具前""拿到结果后""模型思考后"插回调。
这个项目用它把执行过程实时推到网页上——见第 8 篇。

**3. 它不绑死模型**

`model("dashscope:qwen-plus")` 这个字符串是"厂商:模型名"的格式。
换 OpenAI、换本地 Ollama 只改这个字符串。

> **反过来说**：如果你只需要"调一次 API、拿一个回答"，
> 用框架是过度设计。框架的价值在**多轮工具调用**这个场景里。

### 几个必须知道的概念

| 概念 | 在这个项目里是什么 |
|---|---|
| **Agent** | `RagsightDiagnosisAgent`，包着 AgentScope 的 `ReActAgent` |
| **Tool** | `DiagnosisTools` 里那四个方法 |
| **Toolkit** | 装工具并负责把它们暴露给模型的容器 |
| **Message / Msg** | 对话里的每一条（用户说的、模型说的、工具返回的） |
| **System Prompt** | 每轮都发给模型的"工作手册"，规定它怎么干 |
| **maxIters** | 循环最多转几圈 |

### 系统提示词比你想的重要

这个项目的系统提示词里有一段：

```
工作流程：
1. 先调用 run_baseline，拿到一组基准数据。它会告诉你下一步建议往哪试。
2. 然后反复调用 compare_one_change，每次**只改一个东西**。
...
几条必须守住的规矩：
- **一次只改一个参数。** 同时改两个的话，指标变了也不知道是谁的功劳，
  这个对比就白做了。
- **不要自己编数字。** 所有指标都以工具返回的为准。
- **不要自己下结论。** ……工具返回的文字里已经写好了。
```

**为什么写这么细？**

因为不写的话，模型倾向于"一次把所有组合都试一遍"——
那就退化成穷举了，整个第二层设计就白费。

**实测下来它是有效的**：我跑了三次，每次 6 次 `compare_one_change` 全是单变量，
没有一次同时改两样。而且它自己做了两个我没写进提示词的决定：

- 先试"关键词召回"，而不是基准里最优的"向量召回"
- 试到 300 字无增益后，改试更小的 60 字（而不是继续加大）

---

## 改一改试试

### 试 1：看工具注册成什么样

工具注册对不对，是"静默失效"的重灾区：
注解没被识别，模型看到的工具列表就是空的，于是它只会聊天、不干活，**而且不报错**。

这个项目有测试专门验证这件事（`DiagnosisToolRegistrationTest`）。想看它检查了什么：

```bash
# 在仓库根目录
grep -A5 "should_register_all_four_tools" \
  ragsight-agent/src/test/java/cn/qiyec/ragsight/agent/DiagnosisToolRegistrationTest.java
```

### 试 2（有 Key）：改最大轮数

```bash
java -jar ragsight-agent/target/ragsight-agent-exec.jar diagnose --max-iters 3
```

只有 3 轮，它没法把探索做完。**看它到上限时怎么收尾**——
这个"被强行截断时行为如何"是实际用 Agent 时经常遇到的情况。

### 试 3（有 Key）：换成 `-q` 只看结论

```bash
java -jar ragsight-agent/target/ragsight-agent-exec.jar diagnose -q
```

对比一下"看过程"和"只看结论"的差别，感受一下中间过程有多少信息。

---

## 小结

1. **ReAct 循环** = 模型决定调哪个工具 → 工具执行 → 结果回去 → 再决定。循环到模型不再调工具为止。
2. **框架替你做的**：schema 推导、参数校验、结果回传、循环控制、上下文管理。这些和业务无关，但写错就会"看起来正常、其实不对"。
3. **AgentScope 的工具就是带注解的普通方法**，不用继承不用实现。
4. **系统提示词不是随便写的**：它决定模型怎么工作。这个项目靠它把模型约束在"一次只改一个"上。
5. **只调一次 API 的场景不需要框架**。框架的价值在多轮工具调用。

---

## 常见误解

**"Agent 就是会自己思考的大模型。"**

模型本身不会"思考"多轮。**是框架在驱动循环**：
每次把历史对话和工具列表重新发一遍给模型，模型只是"接着往下说"。
所谓"自主"是循环 + 工具 + 提示词共同造出来的效果。

**"工具越多，Agent 越强。"**

工具越多，模型的**选择成本**越高，也越容易选错。
这个项目只给 4 个工具，而且其中两个（`list_attempts`、`finish`）的作用是"帮模型退出循环"。

**"提示词写得越详细越好。"**

过长的提示词会被模型的注意力稀释。
这个项目的提示词重点是"一次只改一个"和"不要自己下结论"这两条硬约束，
其余部分靠**工具自己的 description** 来说（模型看工具描述比看系统提示词更准）。

**"Agent 的输出可以直接当结论用。"**

不行。这个项目里 Agent 只负责"决定试什么"，
**结论仍然由固定规则从指标推导**（见下面第 8 篇）。
让模型写结论，你就失去了"同样的数据得到同样的说法"这个保证。

---

下一篇：[08 让 Agent 自己决定试什么](08-让Agent自己决定试什么.md) —— 这个项目的第二层，
也是整套设计里最难的部分。
