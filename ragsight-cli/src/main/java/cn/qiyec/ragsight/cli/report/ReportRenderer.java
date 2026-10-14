package cn.qiyec.ragsight.cli.report;

import cn.qiyec.ragsight.application.CaseOutcome;
import cn.qiyec.ragsight.application.DiagnosisReport;
import cn.qiyec.ragsight.application.ExperimentId;
import cn.qiyec.ragsight.application.ExperimentResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把诊断报告渲染成 Markdown。
 *
 * <p>为什么用 Markdown：纯文本，能进版本库，终端、编辑器、GitHub 上都能看，
 * 也不需要额外依赖。网页版可以以后再加，Markdown 已经够支撑
 * "跑出数据 → 和同事讨论 → 改配置"这个循环了。
 *
 * <p>这里只负责展示，不做判断。所有结论都是
 * {@link cn.qiyec.ragsight.application.DiagnosisEngine} 用固定规则推出来的，
 * 这里不再加任何新判断，否则同一份数据可能显示出两种结论。
 */
public final class ReportRenderer {

    private ReportRenderer() {
    }

    /** 渲染完整报告。 */
    public static String render(DiagnosisReport report) {
        StringBuilder out = new StringBuilder();
        renderHeader(out, report);
        renderDiagnosis(out, report);
        renderMetricTable(out, report);
        renderDeltas(out, report);
        renderDetail(out, report);
        renderFooter(out);
        return out.toString();
    }

    private static void renderHeader(StringBuilder out, DiagnosisReport report) {
        out.append("# RAG 诊断报告\n\n");
        out.append("- **语料**：").append(report.corpusName()).append('\n');
        out.append("- **测试集**：").append(report.caseCount()).append(" 个用例");
        if (report.negativeCaseCount() > 0) {
            out.append("（含 ").append(report.negativeCaseCount()).append(" 个负样本）");
        }
        out.append('\n');
        out.append("- **对比维度**：").append(report.chunkingCount()).append(" 种切分 × ")
                .append(report.retrieverCount()).append(" 种召回 = ")
                .append(report.experimentCount()).append(" 次实验\n");
        out.append("- **基准**：").append(report.baseline().displayName())
                .append("（报告里的差值都是相对它算的）\n\n");

        // 各切分方案切出来多少片段，是理解后面指标的前提
        out.append("各切分方案切出的片段数：");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : report.corpusSizes().entrySet()) {
            ExperimentResult any = report.results().values().stream()
                    .filter(r -> r.plan().id().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);
            String label = any == null ? entry.getKey() : any.plan().displayName();
            parts.add(label + " → " + entry.getValue() + " 个片段");
        }
        out.append(String.join("；", parts)).append("\n\n");

        out.append("> 指标用固定公式计算，同样的输入一定得到同样的数字。\n");
        out.append("> 结论由规则从数字推导，不经过大模型。\n\n");
    }

    private static void renderDiagnosis(StringBuilder out, DiagnosisReport report) {
        out.append("## 诊断结论\n\n");
        for (String finding : report.findings()) {
            out.append("- ").append(finding).append('\n');
        }
        out.append('\n');
    }

    /**
     * 指标对比表。
     *
     * <p>按切分方案分组展示，而不是把所有实验平铺成一张大表。
     * 因为切分不同的话，"片段总量"都不一样，跨组比指标意义有限；
     * 真正该比的是同一组内不同召回方式的表现。
     */
    private static void renderMetricTable(StringBuilder out, DiagnosisReport report) {
        List<String> metricNames = report.metricNames();

        out.append("## 指标对比\n\n");
        out.append("按切分方案分组。同一组内的行可以直接比；跨组比较要留意片段总数已经变了。\n\n");

        for (String chunkingId : report.chunkingIds()) {
            List<ExperimentResult> group = resultsOfChunking(report, chunkingId);
            if (group.isEmpty()) {
                continue;
            }

            out.append("### ").append(group.get(0).plan().displayName())
                    .append("（").append(report.corpusSizes().getOrDefault(chunkingId, 0))
                    .append(" 个片段）\n\n");

            out.append("| 召回方式 |");
            for (String metric : metricNames) {
                out.append(' ').append(metric).append(" |");
            }
            out.append('\n');

            out.append("|---|");
            for (int i = 0; i < metricNames.size(); i++) {
                out.append("---|");
            }
            out.append('\n');

            for (ExperimentResult result : group) {
                boolean isBaseline = result.id().equals(report.baselineId());
                out.append("| ").append(result.retriever().displayName());
                if (isBaseline) {
                    out.append(" **(基准)**");
                }
                out.append(" |");
                for (String metric : metricNames) {
                    out.append(' ').append(formatScore(result.score(metric))).append(" |");
                }
                out.append('\n');
            }
            out.append('\n');
        }

        out.append("**各指标最佳组合**：");
        for (int i = 0; i < metricNames.size(); i++) {
            String metric = metricNames.get(i);
            ExperimentResult best = report.bestBy(metric);
            if (i > 0) {
                out.append(" · ");
            }
            out.append(metric).append(" → ").append(best.displayName())
                    .append(" (").append(formatScore(best.score(metric))).append(')');
        }
        out.append("\n\n");
    }

    /**
     * 相对基准的变化。
     *
     * <p>报告里最有用的一节：用户关心的是"比现状好多少"。
     */
    private static void renderDeltas(StringBuilder out, DiagnosisReport report) {
        Map<ExperimentId, Map<String, Double>> deltas = report.deltasAgainstBaseline();
        if (deltas.isEmpty()) {
            return;
        }

        List<String> metricNames = report.metricNames();

        out.append("## 相对基准的变化\n\n");
        out.append("| 实验 |");
        for (String metric : metricNames) {
            out.append(' ').append(metric).append(" |");
        }
        out.append('\n');

        out.append("|---|");
        for (int i = 0; i < metricNames.size(); i++) {
            out.append("---|");
        }
        out.append('\n');

        for (Map.Entry<ExperimentId, Map<String, Double>> entry : deltas.entrySet()) {
            ExperimentResult result = report.resultOf(entry.getKey());
            out.append("| ").append(result.displayName()).append(" |");
            for (String metric : metricNames) {
                Double delta = entry.getValue().get(metric);
                out.append(' ').append(formatDelta(delta)).append(" |");
            }
            out.append('\n');
        }
        out.append('\n');
        out.append("> 差值是「这次实验 − 基准」。正数表示更好。\n");
        out.append("> 变化小于 0.05 通常不值得为它增加系统复杂度。\n\n");
    }

    /**
     * 逐用例明细：每个问题在各次实验里，期望片段排到了第几。
     *
     * <p>整体分数只能说明"哪里有问题"，明细才能回答"哪些问题失败了"。
     */
    private static void renderDetail(StringBuilder out, DiagnosisReport report) {
        out.append("## 逐用例明细\n\n");
        out.append("格子里的数字是期望片段的最好排名；`—` 表示没进前 K（没召回到）；")
                .append("`n/a` 表示负样本（本来就没有期望片段）。\n\n");

        List<ExperimentResult> results = List.copyOf(report.results().values());
        List<String> headers = results.stream().map(r -> shortId(r.id())).toList();

        out.append("| 用例 |");
        for (String header : headers) {
            out.append(' ').append(header).append(" |");
        }
        out.append('\n');

        out.append("|---|");
        for (int i = 0; i < results.size(); i++) {
            out.append("---|");
        }
        out.append('\n');

        int caseCount = report.baseline().caseCount();
        for (int caseIndex = 0; caseIndex < caseCount; caseIndex++) {
            out.append("| ").append(report.caseIdAt(caseIndex)).append(" |");
            for (ExperimentResult result : results) {
                out.append(' ').append(rankCell(result, caseIndex)).append(" |");
            }
            out.append('\n');
        }
        out.append('\n');

        // 表头用的是短标识，这里给出完整对照，免得读者猜
        out.append("实验标识对照：\n\n");
        for (ExperimentResult result : results) {
            out.append("- `").append(shortId(result.id())).append("` = ")
                    .append(result.displayName()).append('\n');
        }
        out.append('\n');
    }

    private static void renderFooter(StringBuilder out) {
        out.append("---\n\n");
        out.append("由 [ragsight](https://github.com/qiyec/ragsight) 生成。\n");
        out.append("这个项目是 RAG 质量诊断工具，不实现检索框架本身。\n");
    }

    /** 取某切分方案下的所有实验，按召回方案在请求里的顺序。 */
    private static List<ExperimentResult> resultsOfChunking(DiagnosisReport report,
                                                            String chunkingId) {
        List<ExperimentResult> group = new ArrayList<>();
        for (ExperimentResult result : report.results().values()) {
            if (result.plan().id().equals(chunkingId)) {
                group.add(result);
            }
        }
        return group;
    }

    /** 报告里用的短标识，如 {@code 90/vector}。 */
    private static String shortId(ExperimentId id) {
        String chunking = id.chunkingId();
        String retriever = id.retrieverId();
        return chunking + "/" + retriever;
    }

    /**
     * 取某次实验在某用例上的排名展示。
     *
     * <p>取<b>最好</b>的期望片段排名，和 MRR 的语义一致——
     * 只要有一个期望片段靠前，用户就能拿到正确上下文。
     */
    private static String rankCell(ExperimentResult result, int caseIndex) {
        CaseOutcome outcome = result.outcomeAt(caseIndex);
        if (outcome.isNegative()) {
            return "n/a";
        }
        int best = outcome.bestRank();
        return best > 0 ? String.valueOf(best) : "—";
    }

    /** 分数保留三位小数，方便横向比较。 */
    private static String formatScore(double value) {
        return Double.isNaN(value) ? "n/a" : String.format("%.3f", value);
    }

    /** 差值带正负号，突出方向。 */
    private static String formatDelta(Double delta) {
        if (delta == null || Double.isNaN(delta)) {
            return "n/a";
        }
        if (Math.abs(delta) < 0.0005) {
            return "±0.000";
        }
        return String.format("%+.3f", delta);
    }
}
