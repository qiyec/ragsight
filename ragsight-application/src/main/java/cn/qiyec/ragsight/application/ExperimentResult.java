package cn.qiyec.ragsight.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次实验的结果——某套"切分 + 召回"组合在一份测试集上的表现。
 *
 * <p>这是诊断报告里的一行数据。
 *
 * @param plan        这次用的切分方案和召回条数
 * @param retriever   这次用的召回方案
 * @param metricScores 各指标的分数，键是指标名（如 {@code "Recall@5"}）
 * @param outcomes    每个用例的情况，顺序和测试集一致
 */
public record ExperimentResult(
        ExperimentPlan plan,
        RetrieverConfig retriever,
        Map<String, Double> metricScores,
        List<CaseOutcome> outcomes) {

    public ExperimentResult {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        if (retriever == null) {
            throw new IllegalArgumentException("retriever 不能为空");
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

    /** 这次实验的标识，由切分方案和召回方案拼成。 */
    public ExperimentId id() {
        return new ExperimentId(plan.id(), retriever.id());
    }

    /**
     * 报告里显示的完整名字，比如"递归切分 90 字 + 仅关键词召回"。
     *
     * <p>把两个维度都写出来，用户才不会看错行——
     * 只写召回方式的话，三行数据看起来会一模一样。
     */
    public String displayName() {
        return plan.displayName() + " + " + retriever.displayName();
    }

    /** 取指定指标的分数；没有这个指标时返回 {@code NaN}。 */
    public double score(String metricName) {
        Double value = metricScores.get(metricName);
        return value == null ? Double.NaN : value;
    }

    /**
     * 算这次实验相对基准的提升，按指标给出差值。
     *
     * <p>这是报告里最有用的一列——用户关心的不是"混合检索 Recall 是 0.81"，
     * 而是"比我现在用的方案好多少"。
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

    /** 这次实验覆盖了多少个用例。 */
    public int caseCount() {
        return outcomes.size();
    }

    /** 取指定用例的情况。 */
    public CaseOutcome outcomeAt(int caseIndex) {
        return outcomes.get(caseIndex);
    }
}
