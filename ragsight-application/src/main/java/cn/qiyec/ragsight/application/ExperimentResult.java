package cn.qiyec.ragsight.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次实验的结果——一种检索配置在一份评测集上的表现。
 *
 * <p>这是诊断报告的"一行数据"。
 *
 * @param config      该实验使用的配置
 * @param metricScores 各指标的整体分数，键为指标名（如 {@code "Recall@5"}）
 * @param outcomes    每个用例的归因摘要，顺序与评测集用例顺序一致
 */
public record ExperimentResult(
        RetrieverConfig config,
        Map<String, Double> metricScores,
        List<CaseOutcome> outcomes) {

    public ExperimentResult {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        if (metricScores == null) {
            throw new IllegalArgumentException("metricScores 不能为空");
        }
        if (outcomes == null) {
            throw new IllegalArgumentException("outcomes 不能为空");
        }
        metricScores = Map.copyOf(metricScores);
        outcomes = List.copyOf(outcomes);
    }

    /** 取得指定指标的分数；指标不存在时返回 {@code NaN}。 */
    public double score(String metricName) {
        Double value = metricScores.get(metricName);
        return value == null ? Double.NaN : value;
    }

    /**
     * 计算本实验相对基线实验的各指标差值。
     *
     * <p>这是诊断报告最有价值的一列——用户关心的不是"混合检索 Recall 是 0.81"，
     * 而是"**比我现在用的方案提升了多少**"。
     *
     * @param baseline 基线实验
     * @return 指标名 → 差值（本实验 − 基线）；仅包含两边都有的指标
     */
    public Map<String, Double> deltasAgainst(ExperimentResult baseline) {
        if (baseline == null) {
            throw new IllegalArgumentException("baseline 不能为空");
        }
        Map<String, Double> deltas = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : metricScores.entrySet()) {
            Double base = baseline.metricScores.get(entry.getKey());
            if (base != null && !Double.isNaN(base) && !Double.isNaN(entry.getValue())) {
                deltas.put(entry.getKey(), entry.getValue() - base);
            }
        }
        return Map.copyOf(deltas);
    }

    /** 该实验覆盖的用例数。 */
    public int caseCount() {
        return outcomes.size();
    }

    /** 取指定用例的归因摘要。 */
    public CaseOutcome outcomeAt(int caseIndex) {
        return outcomes.get(caseIndex);
    }
}
