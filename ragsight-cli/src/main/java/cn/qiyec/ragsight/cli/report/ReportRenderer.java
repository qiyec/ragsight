package cn.qiyec.ragsight.cli.report;

import cn.qiyec.ragsight.application.CaseOutcome;
import cn.qiyec.ragsight.application.DiagnosisReport;
import cn.qiyec.ragsight.application.ExperimentResult;
import cn.qiyec.ragsight.application.RetrieverConfig;

import java.util.List;
import java.util.Map;

/**
 * 把诊断报告渲染为 Markdown。
 *
 * <p>Markdown 是首选的报告形态：纯文本可进版本库、可在终端/编辑器/GitHub
 * 任意一处查看，且不需要额外依赖。HTML 报告可以后续再加，
 * 但 Markdown 已足以支撑"跑出数据 → 与团队讨论 → 改动配置"的闭环。
 *
 * <p>渲染只做展示，不做判断——所有结论由
 * {@link cn.qiyec.ragsight.application.DiagnosisEngine} 以确定性规则推导，
 * 这里不引入任何新的判断逻辑，否则同一份数据可能出现两种结论。
 */
public final class ReportRenderer {

    private ReportRenderer() {
    }

    /**
     * 渲染完整报告。
     *
     * @param report 诊断报告
     * @return Markdown 文本
     */
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
        out.append("- **评测集**：").append(report.caseCount()).append(" 个用例");
        if (report.negativeCaseCount() > 0) {
            out.append("（含 ").append(report.negativeCaseCount()).append(" 个负样本）");
        }
        out.append('\n');
        out.append("- **对比配置**：").append(report.experimentCount()).append(" 种\n");
        out.append("- **基准**：").append(report.baseline().config().displayName())
                .append("（报告中所有差值均相对它计算）\n\n");
        out.append("> 指标计算为确定性算法，同一输入必得同一结果。\n");
        out.append("> 结论由规则从指标推导，不经过 LLM。\n\n");
    }

    private static void renderDiagnosis(StringBuilder out, DiagnosisReport report) {
        out.append("## 诊断结论\n\n");
        for (String finding : report.findings()) {
            out.append("- ").append(finding).append('\n');
        }
        out.append('\n');
    }

    /**
     * 渲染指标对比表：行为配置，列为指标。
     */
    private static void renderMetricTable(StringBuilder out, DiagnosisReport report) {
        List<String> metricNames = report.metricNames();

        out.append("## 指标对比\n\n");

        // 表头
        out.append("| 配置 |");
        for (String metric : metricNames) {
            out.append(' ').append(metric).append(" |");
        }
        out.append('\n');

        // 分隔行
        out.append("|---|");
        for (int i = 0; i < metricNames.size(); i++) {
            out.append("---|");
        }
        out.append('\n');

        // 数据行：基准行标记出来，便于对照
        for (ExperimentResult result : report.results().values()) {
            boolean isBaseline = result.config().id().equals(report.baselineConfigId());
            out.append("| ").append(result.config().displayName());
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

        // 各指标最优者
        out.append("**各指标最优**：");
        for (int i = 0; i < metricNames.size(); i++) {
            String metric = metricNames.get(i);
            ExperimentResult best = report.bestBy(metric);
            if (i > 0) {
                out.append(" · ");
            }
            out.append(metric).append(" → ").append(best.config().displayName())
                    .append(" (").append(formatScore(best.score(metric))).append(')');
        }
        out.append("\n\n");
    }

    /**
     * 渲染相对基线的差值表。
     *
     * <p>这是报告最有价值的部分：用户关心的是"比现状好多少"，
     * 而不是绝对分数。
     */
    private static void renderDeltas(StringBuilder out, DiagnosisReport report) {
        Map<String, Map<String, Double>> deltas = report.deltasAgainstBaseline();
        if (deltas.isEmpty()) {
            return;
        }

        out.append("## 相对基准的变化\n\n");

        List<String> metricNames = report.metricNames();
        out.append("| 配置 |");
        for (String metric : metricNames) {
            out.append(' ').append(metric).append(" |");
        }
        out.append('\n');

        out.append("|---|");
        for (int i = 0; i < metricNames.size(); i++) {
            out.append("---|");
        }
        out.append('\n');

        for (Map.Entry<String, Map<String, Double>> entry : deltas.entrySet()) {
            ExperimentResult result = report.resultOf(entry.getKey());
            out.append("| ").append(result.config().displayName()).append(" |");
            for (String metric : metricNames) {
                Double delta = entry.getValue().get(metric);
                out.append(' ').append(formatDelta(delta)).append(" |");
            }
            out.append('\n');
        }
        out.append('\n');
        out.append("> 差值为「本配置 − 基准」。正值表示更好。\n");
        out.append("> 变化幅度低于 0.05 通常不足以支撑增加系统复杂度。\n\n");
    }

    /**
     * 逐用例明细：展示每个问题在各配置下期望证据的排名。
     *
     * <p>整体分数只能说明"哪里有问题"，明细才能回答"哪些问题失败了"。
     * 归因分析必须依赖这一层。
     */
    private static void renderDetail(StringBuilder out, DiagnosisReport report) {
        out.append("## 逐用例明细\n\n");
        out.append("单元格为期望证据在本配置下的排名；`—` 表示未进入前 K（漏召回）。\n\n");

        List<ExperimentResult> results = List.copyOf(report.results().values());

        out.append("| 用例 |");
        for (ExperimentResult result : results) {
            out.append(' ').append(result.config().id()).append(" |");
        }
        out.append('\n');

        out.append("|---|");
        for (int i = 0; i < results.size(); i++) {
            out.append("---|");
        }
        out.append('\n');

        // 以基准配置的用例顺序为准（所有配置共用同一评测集，顺序一致）
        int caseCount = report.baseline().caseCount();
        for (int caseIndex = 0; caseIndex < caseCount; caseIndex++) {
            out.append("| ").append(caseSummary(report, caseIndex)).append(" |");
            for (ExperimentResult result : results) {
                out.append(' ').append(rankCell(result, caseIndex)).append(" |");
            }
            out.append('\n');
        }
        out.append('\n');
    }

    /**
     * 用例的简要在报告里以用例标识呈现。
     */
    private static String caseSummary(DiagnosisReport report, int caseIndex) {
        return report.caseIdAt(caseIndex);
    }

    /**
     * 取某配置在某用例上的排名展示。
     *
     * <p>取**最好**的期望片段排名（与 MRR 语义一致——只要有一个期望片段靠前，
     * 用户就能拿到正确上下文）。负样本无期望片段，显示为 `n/a`。
     */
    private static String rankCell(ExperimentResult result, int caseIndex) {
        CaseOutcome outcome = result.outcomeAt(caseIndex);
        if (outcome.isNegative()) {
            return "n/a";
        }
        int best = outcome.bestRank();
        return best > 0 ? String.valueOf(best) : "—";
    }

    private static void renderFooter(StringBuilder out) {
        out.append("---\n\n");
        out.append("由 [ragsight](https://github.com/qiyec/ragsight) 生成。\n");
        out.append("本项目为 RAG 质量诊断工具，不实现检索框架本身。\n");
    }

    /** 分数格式：保留三位小数，便于横向比较。 */
    private static String formatScore(double value) {
        if (Double.isNaN(value)) {
            return "n/a";
        }
        return String.format("%.3f", value);
    }

    /** 差值格式：带正负号，突出方向。 */
    private static String formatDelta(Double delta) {
        if (delta == null || Double.isNaN(delta)) {
            return "n/a";
        }
        if (Math.abs(delta) < 0.0005) {
            return "±0.000";
        }
        return String.format("%+.3f", delta);
    }

    /** 供外部复用：配置标签。 */
    public static String labelOf(RetrieverConfig config) {
        return config.displayName() + "（" + config.kind().label() + "）";
    }
}
