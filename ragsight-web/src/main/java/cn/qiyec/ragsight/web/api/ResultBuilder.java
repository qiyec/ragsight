package cn.qiyec.ragsight.web.api;

import cn.qiyec.ragsight.application.DiagnosisReport;
import cn.qiyec.ragsight.application.ExperimentResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link DiagnosisReport} 转成前端要的形状。
 *
 * <p>单独一个类，是因为这段转换只做一件事：**重新组织数据**。
 * 不做诊断、不做判断。所有结论都是引擎推出来的，这里只负责摆位置。
 *
 * <p>拆出来还有个好处：这堆转换逻辑可以单独测，
 * 不用起 HTTP 服务，也不用真的跑一遍诊断。
 */
public final class ResultBuilder {

    private ResultBuilder() {
    }

    /**
     * 转换。
     *
     * @param report        诊断报告
     * @param topK          这一轮用的 topK，用来拼指标名
     * @param elapsedMillis 跑这轮花了多久
     */
    public static ApiDtos.DiagnoseResult build(DiagnosisReport report,
                                               int topK,
                                               long elapsedMillis) {
        List<String> metricNames = metricNames(topK);

        return new ApiDtos.DiagnoseResult(
                report.corpusName(),
                report.documentCount(),
                report.caseCount(),
                report.negativeCaseCount(),
                report.experimentCount(),
                report.baseline().displayName(),
                metricNames,
                buildRows(report, metricNames),
                buildFindings(report),
                buildHeatmap(report, "MRR@" + topK),
                buildChart(report, topK),
                elapsedMillis);
    }

    /**
     * 指标名按固定顺序给。
     *
     * <p>不跟着 Map 的迭代顺序走——每次刷新列的顺序都不一样会很奇怪。
     */
    private static List<String> metricNames(int topK) {
        return List.of("Recall@" + topK, "MRR@" + topK, "nDCG@" + topK);
    }

    private static List<ApiDtos.DiagnoseResult.Row> buildRows(DiagnosisReport report,
                                                              List<String> metricNames) {
        Map<cn.qiyec.ragsight.application.ExperimentId, Map<String, Double>> deltas =
                report.deltasAgainstBaseline();

        List<ApiDtos.DiagnoseResult.Row> rows = new ArrayList<>();
        for (ExperimentResult result : report.results().values()) {
            Map<String, Double> scores = new LinkedHashMap<>();
            for (String metric : metricNames) {
                scores.put(metric, result.score(metric));
            }
            Map<String, Double> delta = deltas.get(result.id());
            rows.add(new ApiDtos.DiagnoseResult.Row(
                    result.plan().displayName(),
                    result.retriever().displayName(),
                    result.plan().rerank(),
                    result.id().equals(report.baselineId()),
                    scores,
                    delta == null ? Map.of() : delta));
        }
        return List.copyOf(rows);
    }

    /**
     * 给结论分个严重程度，前端据此上色。
     *
     * <p>判断办法很笨：看文本的前缀和关键词。这么做是因为结论文案由
     * {@code DiagnosisEngine} 生成，那里不该关心前端怎么显示。
     * 与其在引擎里塞一个"显示用"的字段，不如在这里做一次粗糙的映射。
     *
     * <p>风险是前端的颜色可能和文案意图不完全一致。所以分级只是
     * **视觉提示**，真正的信息在文案本身——即使颜色分错了，
     * 用户读文字也不会误解。
     */
    private static List<ApiDtos.DiagnoseResult.Finding> buildFindings(DiagnosisReport report) {
        List<ApiDtos.DiagnoseResult.Finding> findings = new ArrayList<>();
        for (String text : report.findings()) {
            findings.add(new ApiDtos.DiagnoseResult.Finding(text, levelOf(text)));
        }
        return List.copyOf(findings);
    }

    private static ApiDtos.DiagnoseResult.Finding.Level levelOf(String text) {
        if (text.startsWith("✅")) {
            return ApiDtos.DiagnoseResult.Finding.Level.GOOD;
        }
        if (text.startsWith("⚠")) {
            return ApiDtos.DiagnoseResult.Finding.Level.WARN;
        }
        if (text.contains("反而变差") || text.contains("异常")) {
            return ApiDtos.DiagnoseResult.Finding.Level.BAD;
        }
        return ApiDtos.DiagnoseResult.Finding.Level.INFO;
    }

    /**
     * 造热力图：行是切分方案，列是召回方式。
     *
     * <p>这是整个页面最有价值的东西。一次诊断会跑 9 到 12 次实验，
     * 列成表格要逐行读；画成热力图，哪个组合好、切分有多大影响，
     * 一眼就能看出来。
     *
     * <p><b>行标签要区分"带不带重排"</b>。不带的话，
     * "递归切分 90 字"和"递归切分 90 字 + 重排"会挤在同一行上，
     * 后一个的值会把前一个覆盖掉。
     */
    private static ApiDtos.DiagnoseResult.Heatmap buildHeatmap(DiagnosisReport report,
                                                               String metricName) {
        // 按报告里的顺序收集行、列，保证每次渲染顺序一致
        Set<String> rowKeys = new LinkedHashSet<>();
        Set<String> columnKeys = new LinkedHashSet<>();
        Map<String, ExperimentResult> byPosition = new LinkedHashMap<>();

        for (ExperimentResult result : report.results().values()) {
            String rowKey = rowLabel(result);
            String columnKey = result.retriever().displayName();
            rowKeys.add(rowKey);
            columnKeys.add(columnKey);
            byPosition.put(rowKey + "\u0000" + columnKey, result);
        }

        List<String> rowLabels = List.copyOf(rowKeys);
        List<String> columnLabels = List.copyOf(columnKeys);
        double[][] values = new double[rowLabels.size()][columnLabels.size()];

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (int row = 0; row < rowLabels.size(); row++) {
            for (int col = 0; col < columnLabels.size(); col++) {
                ExperimentResult result = byPosition.get(
                        rowLabels.get(row) + "\u0000" + columnLabels.get(col));
                double value = result == null ? Double.NaN : result.score(metricName);
                values[row][col] = value;
                if (!Double.isNaN(value)) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
        }

        // 一个值都没有时给个安全的范围，免得前端算颜色时除零
        if (min > max) {
            min = 0;
            max = 1;
        }

        return new ApiDtos.DiagnoseResult.Heatmap(
                metricName, rowLabels, columnLabels, values, min, max);
    }

    /**
     * 热力图的行标签。
     *
     * <p>直接用切分方案的展示名，不再自己拼"（重排）"——
     * 因为加了重排的方案，它自己的名字里已经带了这四个字
     * （{@code ExperimentPlan} 的展示名就是"递归切分 90 字 + 重排"），
     * 再拼一次会变成"递归切分 90 字 + 重排（重排）"。
     *
     * <p>这里依赖一个约定：**同一批实验里，不同切分方案的展示名必须不同**。
     * 加方案时如果两个方案取了一样的展示名，热力图的两行会挤成一行。
     */
    private static String rowLabel(ExperimentResult result) {
        return result.plan().displayName();
    }

    /**
     * 柱状图数据：按 MRR 从高到低排，好的一眼看到。
     */
    private static ApiDtos.DiagnoseResult.Chart buildChart(DiagnosisReport report, int topK) {
        List<ExperimentResult> ordered = report.rankedBy("MRR@" + topK);

        List<String> labels = new ArrayList<>();
        double[] recall = new double[ordered.size()];
        double[] mrr = new double[ordered.size()];
        double[] ndcg = new double[ordered.size()];

        for (int i = 0; i < ordered.size(); i++) {
            ExperimentResult result = ordered.get(i);
            labels.add(result.displayName());
            recall[i] = result.score("Recall@" + topK);
            mrr[i] = result.score("MRR@" + topK);
            ndcg[i] = result.score("nDCG@" + topK);
        }

        String best = ordered.isEmpty() ? "" : ordered.get(0).displayName();
        return new ApiDtos.DiagnoseResult.Chart(
                List.copyOf(labels), recall, mrr, ndcg, best);
    }
}
