# ragsight

RAG 用起来容易，出了问题难查。

效果不好，改切分、换 embedding、加重排，一轮轮试下来，有时候好一点，有时候白忙。
但"到底是哪一步不行"这个问题，一直没答案。

ragsight 就是量这个的。它把检索拆成几个能单独测的环节，各给一个分数，
然后告诉你是召回没捞到，还是捞到了排得不好，还是切分把答案切碎了。

不是另一个 RAG 框架。那一层已经不缺人了。

---

## 跑一下

需要 JDK 17。Maven 不用装，仓库里带了。

```bash
git clone https://github.com/qiyec/ragsight.git
cd ragsight
mvnw.cmd clean package -DskipTests     # Windows
./mvnw clean package -DskipTests       # macOS / Linux
java -jar ragsight-cli/target/ragsight-cli.jar diagnose
```

不用 API Key，不用数据库，不用联网。跑的是内置的几篇演示文档。

看中文乱码的话加两个参数：

```bash
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar ragsight-cli/target/ragsight-cli.jar diagnose
```

---

## 它会说什么

下面这段是上面那条命令输出里的「诊断结论」一节，原样贴的：

```
- 4 个文档，10 个用例（负样本 2 个），共跑 9 次实验
- 基准（递归切分 90 字 + 仅向量召回）Recall@5 = 0.900，召回基本够用，瓶颈更可能在排序环节。
- 切分确实有影响：「递归切分 200 字」的 Recall@5 最高（1.000），比其他切分方案至少高 0.100。
- ⚠ 但别急着下结论：片段切得越大，一个片段里装的内容越多，命中率天然就越高——这不一定说明检索变好了。还要看喂给模型的上下文里有多少是无关内容（片段越大越多），以及总片段数是否少到「返回前 K 条」已经接近返回全部。
- 以下方案相对基准没什么变化（提升都小于 0.05），不值得为此增加复杂度：向量+关键词 融合
```

第三行是这个项目最想说的话。调 RAG 时最容易做的错事就是把片段越切越大，
分数涨了，其实是把更多内容塞进同一个片段，喂给模型的东西也更杂。

第四行说另一件事：它会告诉你哪些改动不值得做。很多"优化"的收益还不如它带来的复杂度，
只是没人量过，就一直加着。

这些结论是固定公式从指标算出来的，不经过大模型。同一份数据跑两次，说的话一模一样。
不然没人敢拿它去改生产配置。

---

## 三个入口

命令行（上面那个）。适合脚本和 CI。

网页。能看热力图，也能实时看 Agent 思考：

```bash
java -jar ragsight-web/target/ragsight-web-exec.jar
```

然后浏览器打开 `http://localhost:8080`。
注意是在地址栏输地址，不是双击 `index.html`——那样打不开接口。

Agent 自己探索。这个需要大模型凭据：

```bash
$env:DASHSCOPE_API_KEY = "你的key"
java -jar ragsight-agent/target/ragsight-agent-exec.jar diagnose
```

它先跑基准，看结果，再决定下一步改什么。通常七八步就收工，不是把所有组合挨个试一遍。

---

## 想让数字有意义，得接真实模型

默认的"向量化"是离线的，不联网不用 Key，但它只认字面重合。
拿它跑出来的分数只能验证流程通不通，不能用来做决策。

差别很大。同一个问题（`examples/corpus` 那份语料，递归切分 90 字，只看向量召回）：

| 用哪种向量化 | MRR@5 |
|---|---|
| 离线（默认） | 0.688 |
| `text-embedding-v4` | 1.000 |

连"该用向量还是该用关键词"的结论都会反过来：
用离线跑，关键词召回更高，会建议你换关键词；
用真实模型跑，向量召回满分，换了反而是退步。

接真实模型：

```bash
$env:RAGSIGHT_API_KEY = "你的key"
java -jar ragsight-cli/target/ragsight-cli.jar diagnose \
     --corpus examples/corpus --eval examples/eval.txt \
     --embedding-model text-embedding-v4
```

默认连阿里 DashScope 的兼容接口。换别家改 `--embedding-base-url` 和模型名就行，
不用改代码。

---

## 文档

| 想干什么 | 去哪看 |
|---|---|
| 学 RAG 和 Agent | [docs/learn](docs/learn/) — 9 篇，每篇都有能跑的命令 |
| 读代码、改代码 | [docs/design](docs/design/) — 分层、模块、二次开发步骤 |
| 知道为什么这么设计 | [docs/adr](docs/adr/) — 5 篇决策记录，含被否掉的方案 |
| 看进度和踩过的坑 | [PROGRESS.md](PROGRESS.md) |

---

## 现在做到哪了

两层都通了。

第一层算指标、跑对比，纯计算，结果可复现。第二层是让 Agent 自己决定试什么，
会调大模型，但只决定"试什么"，不下结论。

还没做的：更多切分策略（现在 2 种）、向量数据库（现在是内存暴力算，
几万片段以内够用）、大语料压测、网页那个 Agent 入口没在真浏览器里点过。

详细的在 [PROGRESS.md](PROGRESS.md)。

---

## 许可证

Apache 2.0
