package cn.qiyec.ragsight.agent;

import cn.qiyec.ragsight.application.ExperimentPlan;
import cn.qiyec.ragsight.application.RetrieverConfig;
import cn.qiyec.ragsight.diagnosis.DiagnosisService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;

/**
 * 给诊断 Agent 用的工具集。
 *
 * <p><b>设计要点：每个工具只改一个东西。</b>
 *
 * <p>这是整个第二层的关键。如果开放的接口是"跑 N 种切分 × M 种召回"，
 * 那 Agent 能做的只是把穷举换个方式跑一遍，得不到任何新东西。
 * 所以这里开放的是"把某一个东西从 A 改成 B，看差别"——
 * 每次改动的效果都能单独归因。
 *
 * <p>举个数：语料 14 个片段、3 种切分 × 3 种召回 × 2 种重排 = 18 组。
 * 穷举要跑 18 次。但 Agent 通常 3 到 4 次就够：
 *
 * <pre>
 *   跑基准              → 发现召回率已经是 1.000，MRR 只有 0.688
 *   试"加重排"           → 看它能不能把排序提上去
 *   试"换个召回方式"      → 看是不是召回方式的问题
 *   套用当前最好的配置    → 结束
 * </pre>
 *
 * <p><b>工具只报数字，不给结论。</b> 结论（"该不该改切分"这类）仍然由
 * {@code DiagnosisEngine} 的固定规则推导，写进 {@code findings}，
 * 由 {@link #finish} 原样带出来。这样同一份数据，
 * 不管是大模型挑的方案还是网页挑的方案，结论口径都一样。
 */
public class DiagnosisTools {

    private final DiagnosisSession session;

    public DiagnosisTools(DiagnosisSession session) {
        this.session = session;
    }

    // ------------------------------------------------------------------
    // 第一步：建立基准
    // ------------------------------------------------------------------

    @Tool(name = "run_baseline",
            description = """
                    跑一组基准实验，作为后面所有比较的参照。这是第一步，必须先调用。

                    它会用你指定的切分窗口，跑这几种组合：
                    递归切分、固定窗口切分，各自搭配三种召回方式（仅向量、仅关键词、向量+关键词融合）。
                    所以一次调用会得到 6 组数据，可以看出「切分策略」和「召回方式」各自的影响。

                    返回每组的 Recall / MRR / nDCG，以及一句提示：下一步建议改哪个方向。
                    """)
    public String runBaseline(
            @ToolParam(name = "chunk_size",
                    description = "切分窗口大小（字符）。常用的有 60 / 90 / 120 / 200 / 300。越大片段越少越长")
            int chunkSize) {

        if (session.experimentCount() > 0) {
            return "注意：已经跑过基准了。当前最好的是 " + describeBest()
                    + "。想改什么请用 compare_one_change，或者用 finish 收尾。";
        }

        int overlap = session.chunkOverlap();
        List<ExperimentPlan> plans = List.of(
                DiagnosisService.plan(DiagnosisService.STRATEGY_RECURSIVE,
                        chunkSize, overlap, session.topK(), false),
                DiagnosisService.plan(DiagnosisService.STRATEGY_FIXED,
                        chunkSize, overlap, session.topK(), false));

        List<DiagnosisSession.Attempted> results =
                session.runAndUpdateBest(plans, RetrieverConfig.standardBaselines());

        StringBuilder sb = new StringBuilder();
        sb.append("基准结果（语料：").append(session.corpusLabel())
                .append("，召回条数 ").append(session.topK()).append("）：\n\n");
        sb.append(format(results));
        sb.append("\n当前最好的一组：").append(describeBest()).append('\n');

        // 直接把"下一步该往哪试"摆出来。让 Agent 自己从数字里猜方向
        // 是可行的，但明确说一句能少绕弯路。
        DiagnosisSession.Attempted best = session.best();
        if (best != null) {
            sb.append("\n下一步建议：\n");
            if (best.recall() < 0.8) {
                sb.append("- 召回率只有 ").append(fmt(best.recall()))
                        .append("，有相当一部分期望内容根本没进候选。")
                        .append("这种情况先别管排序，优先想办法提高召回：")
                        .append("可以试用 compare_one_change 换个 chunk_size 或切分策略。\n");
            } else {
                sb.append("- 召回率已经 ").append(fmt(best.recall()))
                        .append("，基本够用，瓶颈更可能在排序（MRR ").append(fmt(best.mrr()))
                        .append("）。可以试 compare_one_change 把 rerank 设成 true 看能不能提上去。\n");
            }
            sb.append("- 也可以试换个召回方式（retriever 设成 vector / keyword / hybrid）。\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 核心：单变量对比
    // ------------------------------------------------------------------

    @Tool(name = "compare_one_change",
            description = """
                    把当前最好的配置只改一个地方，跟改之前对比，看这个改动有没有用。

                    这是主要的工作工具。每次只改一个参数，所以效果能明确归因到那一个改动上。

                    比"一次改好几个"强的地方：如果同时改了切分和重排，指标变了也不知道是谁的功劳。
                    这个工具会明确告诉你「只改了 X，结果是变好还是变差、差多少」。

                    返回值里会说明这次改动值不值得采用，以及还剩哪些方向没试过。
                    """)
    public String compareOneChange(
            @ToolParam(name = "change",
                    description = """
                            要改哪一个。只能是下面四个之一：
                            chunk_size   —— 改切分窗口大小（这时 new_value 填数字，如 120）
                            strategy     —— 换切分策略（new_value 填 recursive 或 fixed）
                            retriever    —— 换召回方式（new_value 填 vector / keyword / hybrid）
                            rerank       —— 改要不要重排（new_value 填 true 或 false）
                            """)
            String change,

            @ToolParam(name = "new_value",
                    description = "改成什么。含义取决于 change，见上面的说明。")
            String newValue) {

        if (session.best() == null) {
            return "还没跑过基准。请先调用 run_baseline，否则没有比较的起点。";
        }

        DiagnosisSession.Attempted current = session.best();

        String problem = validateChange(change, newValue, current);
        if (problem != null) {
            return problem;
        }

        ExperimentPlan changed = buildChangedPlan(change, newValue, current);
        RetrieverConfig changedRetriever = buildChangedRetriever(change, newValue, current);

        // 把"改前"和"改后"放进同一批跑，这样两者用的是同一份语料、
        // 同一个 topK，指标可以直接比
        ExperimentPlan before = DiagnosisService.plan(
                strategyOf(current), chunkSizeOf(current), session.chunkOverlap(),
                session.topK(), current.planLabel().contains("重排"));
        RetrieverConfig beforeRetriever = retrieverById(current.retrieverId());

        // 防线：这一改实际上什么都没变时早点说清，别白跑一次。
        // 校验那边已经拦了大部分，这里是兜底——
        // 比如模型同时给出了一个不会生效的组合。
        if (before.id().equals(changed.id())
                && beforeRetriever.id().equals(changedRetriever.id())) {
            return String.format(
                    "这次改动实际上什么都没变（还是「%s · %s」），所以没法对比。"
                            + "请换一个真正不同的值，或者用 finish 收尾。",
                    before.displayName(), beforeRetriever.displayName());
        }

        // 用 runForComparison：这批里"改之前"不是要试的新方案，
        // 不该让它参与"当前最好"的竞争（否则改之后变差时，
        // 最好会被回滚到旧配置，表现上像是改动没生效）
        List<DiagnosisSession.Attempted> results = session.runForComparison(
                List.of(before, changed),
                List.of(beforeRetriever, changedRetriever));

        DiagnosisSession.Attempted beforeResult = pick(results, current);
        DiagnosisSession.Attempted afterResult = results.stream()
                .filter(a -> !a.key().equals(beforeResult.key()))
                .findFirst()
                .orElse(null);

        if (afterResult == null) {
            return "这次改动实际上什么都没变（改之前和改之后是同一个配置），"
                    + "所以没法对比。请换一个真正不同的值，或者用 finish 收尾。";
        }

        // 只有真的更好才更新"当前最好"
        session.applyIfBetter(afterResult);

        double deltaMrr = afterResult.mrr() - beforeResult.mrr();
        double deltaRecall = afterResult.recall() - beforeResult.recall();

        StringBuilder sb = new StringBuilder();
        sb.append("只改了一处：").append(changeLabel(change, newValue, current)).append("\n\n");
        sb.append(String.format("  改之前  %-34s Recall %s  MRR %s%n",
                beforeResult.displayName(), fmt(beforeResult.recall()), fmt(beforeResult.mrr())));
        sb.append(String.format("  改之后  %-34s Recall %s  MRR %s%n",
                afterResult.displayName(), fmt(afterResult.recall()), fmt(afterResult.mrr())));
        sb.append(String.format("  变化    Recall %s  MRR %s%n%n",
                signed(deltaRecall), signed(deltaMrr)));

        if (Math.abs(deltaMrr) < 0.005 && Math.abs(deltaRecall) < 0.005) {
            sb.append("结论：这个改动基本没影响，不值得为它增加复杂度。\n");
        } else if (deltaMrr > 0.005) {
            sb.append("结论：这个改动有效，排序质量提升了。当前最好的配置已更新为它。\n");
        } else {
            sb.append("结论：这个改动让排序变差了。不要采用。\n");
            if (Math.abs(deltaRecall) >= 0.05 && deltaRecall > 0) {
                sb.append("      注意：召回率同时上升了，这是「多要了候选」带来的，")
                        .append("不完全是排序变好。\n");
            }
        }

        sb.append("\n还没试过的方向：").append(remainingIdeas()).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 查看与收尾
    // ------------------------------------------------------------------

    @Tool(name = "list_attempts",
            description = "列出已经跑过的所有配置和它们的指标，以及当前最好的是哪个。")
    public String listAttempts() {
        if (session.attempted().isEmpty()) {
            return "还没跑过任何配置。请先调用 run_baseline。";
        }
        StringBuilder sb = new StringBuilder("跑过的配置（按跑的顺序）：\n\n");
        sb.append(format(session.attempted()));
        sb.append("\n当前最好：").append(describeBest()).append('\n');
        sb.append("一共跑了 ").append(session.experimentCount()).append(" 次实验。\n");
        return sb.toString();
    }

    @Tool(name = "finish",
            description = """
                    收尾，给出最终结论。当已经找到明显更好的配置，或者试了几个方向都没什么效果时调用。

                    它会汇总：当前最好的配置是什么、相对于起点提升了多少、一共跑了多少次实验、
                    以及诊断引擎根据指标给出的结论（瓶颈在召回还是排序、切分有没有影响等）。
                    """)
    public String finish() {
        if (session.best() == null) {
            return "还没跑过任何配置，没有结论可给。请先调用 run_baseline。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("========== 诊断结论 ==========\n\n");
        sb.append("语料：").append(session.corpusLabel()).append('\n');
        sb.append("一共跑了 ").append(session.experimentCount()).append(" 次实验\n\n");

        // 老实交代"这次到底省没省"。
        //
        // 之前这里写死了一句"穷举全部组合要跑 18 次"——那是错的：
        // 穷举的次数取决于**试了几个切分窗口**，每换一个窗口就是
        // 一整套新的组合。Agent 探索得越多，穷举的对照数就越大，
        // 但 Agent 自己的开销也同比涨。
        //
        // 真实跑下来就撞到过这种情况：Agent 在 120 字那一档
        // 试了 6 步、跑了 16 次，而"切分窗口固定 120 字"的穷举只要 12 次——
        // 也就是**它反而跑多了**。
        //
        // 与其给一个漂亮但站不住的对比，不如把这个数算清楚再报，
        // 并且明确说出"这次没省"。
        int exploredChunkSizes = session.triedChunkSizes().size();
        int gridPerChunkSize = 3 * 2;   // 3 种召回 × 2 种重排
        int bruteForce = exploredChunkSizes * gridPerChunkSize;
        int actual = session.experimentCount();

        sb.append("对照：你在 ").append(exploredChunkSizes).append(" 种切分窗口上探索。")
                .append("如果把每种窗口的所有组合都跑一遍，是 ")
                .append(exploredChunkSizes).append(" × 3 种召回 × 2 种重排 = ")
                .append(bruteForce).append(" 次。\n");
        if (actual < bruteForce) {
            sb.append("你跑了 ").append(actual).append(" 次，省了 ")
                    .append(bruteForce - actual).append(" 次。\n\n");
        } else {
            sb.append("你跑了 ").append(actual).append(" 次——**这次没比穷举省**。")
                    .append("原因是每做一次对比都要把「改之前」重跑一遍作对照，")
                    .append("而语料小、单次实验只要几十毫秒，这个开销占比就上来了。\n")
                    .append("维度一多（或者单次实验变贵）差距才会拉开。\n\n");
        }

        sb.append("推荐配置：").append(describeBest()).append('\n');
        sb.append(String.format("  Recall %s   MRR %s   nDCG %s%n%n",
                fmt(session.best().recall()),
                fmt(session.best().mrr()),
                fmt(session.best().ndcg())));

        sb.append("诊断引擎给出的结论：\n");
        List<String> findings = session.allFindings();
        if (findings.isEmpty()) {
            sb.append("  （没有额外结论）\n");
        } else {
            findings.stream().distinct().forEach(f -> sb.append("  · ").append(f).append('\n'));
        }

        sb.append("\n说明：上面的结论是诊断引擎用固定规则从指标推出来的，")
                .append("不是大模型写的。所以同样的数据每次都会得到同样的说法。\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 内部：把 Agent 的"改什么"翻译成实验方案
    // ------------------------------------------------------------------

    /** 检查 change 和 new_value 是否合法。返回 null 表示没问题。 */
    private String validateChange(String change, String newValue,
                                  DiagnosisSession.Attempted current) {
        if (change == null || change.isBlank()) {
            return "change 不能为空。可选：chunk_size / strategy / retriever / rerank";
        }
        switch (change) {
            case "chunk_size" -> {
                try {
                    int v = Integer.parseInt(newValue.trim());
                    if (v <= session.chunkOverlap()) {
                        return "切分窗口要大于重叠字符数（" + session.chunkOverlap()
                                + "），你给的是 " + v;
                    }
                    if (chunkSizeOf(current) == v) {
                        return "当前最好用的就是 " + v + " 字的切分，换个别的数试试。"
                                + "（已经试过：" + session.triedChunkSizes() + "）";
                    }
                } catch (NumberFormatException e) {
                    return "chunk_size 的 new_value 必须是数字，你给的是：" + newValue;
                }
            }
            case "strategy" -> {
                String v = newValue == null ? "" : newValue.trim();
                if (!DiagnosisService.STRATEGY_RECURSIVE.equals(v)
                        && !DiagnosisService.STRATEGY_FIXED.equals(v)) {
                    return "strategy 只能是 recursive 或 fixed，你给的是：" + newValue;
                }
                if (strategyOf(current).equals(v)) {
                    return "当前最好用的就是 " + v + " 策略，换成另一个试试。";
                }
            }
            case "retriever" -> {
                String v = newValue == null ? "" : newValue.trim();
                var known = RetrieverConfig.standardBaselines().stream()
                        .map(RetrieverConfig::id).toList();
                if (!known.contains(v)) {
                    return "retriever 只能是 " + known + " 之一，你给的是：" + newValue;
                }
                if (current.retrieverId().equals(v)) {
                    return "当前最好用的就是 " + v + " 召回，换另一个试试。";
                }
            }
            case "rerank" -> {
                String v = newValue == null ? "" : newValue.trim().toLowerCase();
                if (!v.equals("true") && !v.equals("false")) {
                    return "rerank 的 new_value 只能是 true 或 false，你给的是：" + newValue;
                }
                boolean want = Boolean.parseBoolean(v);
                if (current.planLabel().contains("重排") == want) {
                    return "当前最好的配置本来" + (want ? "就带了" : "就没带")
                            + "重排，换成" + (want ? "不带" : "带") + "重排才能比。";
                }
            }
            default -> {
                return "不认识的 change：" + change
                        + "。可选：chunk_size / strategy / retriever / rerank";
            }
        }
        return null;
    }

    /** 按改动造出"改之后"的切分方案。retriever 的改动不影响它。 */
    private ExperimentPlan buildChangedPlan(String change, String newValue,
                                            DiagnosisSession.Attempted current) {
        String strategy = switch (change) {
            case "strategy" -> newValue.trim();
            default -> strategyOf(current);
        };
        int chunkSize = switch (change) {
            case "chunk_size" -> Integer.parseInt(newValue.trim());
            default -> chunkSizeOf(current);
        };
        boolean rerank = switch (change) {
            case "rerank" -> Boolean.parseBoolean(newValue.trim());
            default -> current.planLabel().contains("重排");
        };
        return DiagnosisService.plan(strategy, chunkSize, session.chunkOverlap(),
                session.topK(), rerank);
    }

    /**
     * 按改动造出"改之后"的召回方式。
     *
     * <p>用具名工厂方法而不是按 id 查表。查表写法出过一个真 bug：
     * 查不到时会退回列表第一个，如果那个恰好和"改之前"是同一个，
     * 同一批实验里就会出现两个同 id 的召回方案，
     * {@code DiagnosisReport} 的校验会拦下来（它拦得对——
     * 报告里两行一样的分不清哪个是哪个）。
     *
     * <p>这里的合法性由 {@link #validateChange} 保证：能走到这里，
     * {@code newValue} 一定是三个已知 id 之一。
     */
    private RetrieverConfig buildChangedRetriever(String change, String newValue,
                                                  DiagnosisSession.Attempted current) {
        String id = "retriever".equals(change) ? newValue.trim() : current.retrieverId();
        return retrieverById(id);
    }

    /** 按 id 取召回方案。取不到直接抛错，不悄悄退回别的。 */
    private static RetrieverConfig retrieverById(String id) {
        return RetrieverConfig.standardBaselines().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不认识的召回方式：" + id));
    }

    /** 在结果里找出和当前最好对应的那一条。 */
    private DiagnosisSession.Attempted pick(List<DiagnosisSession.Attempted> results,
                                            DiagnosisSession.Attempted current) {
        return results.stream()
                .filter(a -> a.key().equals(current.key()))
                .findFirst()
                // 理论上一定有；找不到就退回第一条，别让工具直接抛异常给 Agent
                .orElse(results.get(0));
    }

    private String strategyOf(DiagnosisSession.Attempted a) {
        return a.planLabel().contains("固定窗口")
                ? DiagnosisService.STRATEGY_FIXED
                : DiagnosisService.STRATEGY_RECURSIVE;
    }

    private int chunkSizeOf(DiagnosisSession.Attempted a) {
        var m = java.util.regex.Pattern.compile("(\\d+)\\s*字").matcher(a.planLabel());
        // 解析不出来时退回一个安全值。方案名都是自己拼的，正常不会走到这。
        return m.find() ? Integer.parseInt(m.group(1)) : 90;
    }

    private String changeLabel(String change, String newValue,
                               DiagnosisSession.Attempted current) {
        return switch (change) {
            case "chunk_size" -> "切分窗口 " + chunkSizeOf(current) + " 字 → " + newValue + " 字";
            case "strategy" -> "切分策略 " + strategyOf(current) + " → " + newValue;
            case "retriever" -> "召回方式 " + current.retrieverLabel() + " → " + newValue;
            // 重排要说"开/关"，不要直接吐模型给的 true/false——
            // 报告是给人看的，中文里"关 → true"读起来很别扭
            case "rerank" -> "重排 " + (current.planLabel().contains("重排") ? "开 → 关" : "关 → 开");
            default -> change + " → " + newValue;
        };
    }

    /** 还能往哪些方向试。减少 Agent 反复试同一个东西。 */
    private String remainingIdeas() {
        List<String> ideas = new java.util.ArrayList<>();
        List<Integer> tried = session.triedChunkSizes();
        for (int size : DiagnosisService.chunkSizeOptions()) {
            if (!tried.contains(size)) {
                ideas.add("chunk_size=" + size);
            }
        }
        if (!session.triedRerank()) {
            ideas.add("rerank=true");
        }
        List<String> triedRetrievers = session.triedRetrievers();
        for (RetrieverConfig c : RetrieverConfig.standardBaselines()) {
            if (!triedRetrievers.contains(c.displayName())) {
                ideas.add("retriever=" + c.id());
            }
        }
        return ideas.isEmpty() ? "（主要方向都试过了，可以用 finish 收尾）" : String.join("、", ideas);
    }

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    private String format(List<DiagnosisSession.Attempted> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-30s %-16s %8s %8s %8s%n",
                "切分方案", "召回方式", "Recall", "MRR", "nDCG"));
        for (DiagnosisSession.Attempted a : results) {
            sb.append(String.format("  %-30s %-16s %8s %8s %8s%n",
                    a.planLabel(), a.retrieverLabel(),
                    fmt(a.recall()), fmt(a.mrr()), fmt(a.ndcg())));
        }
        return sb.toString();
    }

    private String describeBest() {
        DiagnosisSession.Attempted best = session.best();
        if (best == null) {
            return "（还没跑过）";
        }
        return String.format("%s（Recall %s，MRR %s）",
                best.displayName(), fmt(best.recall()), fmt(best.mrr()));
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "—" : String.format("%.3f", v);
    }

    private static String signed(double v) {
        if (Math.abs(v) < 0.0005) {
            return "±0.000";
        }
        return String.format("%+.3f", v);
    }
}
