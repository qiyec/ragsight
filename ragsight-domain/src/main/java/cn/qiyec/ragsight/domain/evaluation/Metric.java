package cn.qiyec.ragsight.domain.evaluation;

import java.util.List;

/**
 * 评估指标接口。
 *
 * <p>所有指标都是纯计算：给定测试集和实际召回结果，输出唯一确定的分数。
 * 这是结论可复现的前提——绝不能引入大模型或随机数（见 ADR-001）。
 *
 * <p><b>为什么让每个指标自己处理负样本</b>：负样本（期望集合为空，
 * 也就是"这个问题本来就该答不出来"）在不同指标里的含义不一样。
 * 召回率类指标里它应该算满分（没有该找的东西，就不存在漏找）；
 * 而排序质量类指标里它没有定义，应该直接不计入。
 * 这个差别是语义上的，不该让调用方去统一处理。
 */
public interface Metric {

    /** 指标名称，如 {@code "Recall@5"}。用于报告展示和结果对比。 */
    String name();

    /**
     * 给单个用例打分。
     *
     * @param testCase 标注用例
     * @param evidence 该用例实际召回的结果
     * @return 分数。召回类指标在 [0,1]；排序类指标遇到负样本时返回
     *         {@link Double#NaN}，表示"这个用例不参与平均"
     */
    double score(GoldenCase testCase, RetrievedEvidence evidence);

    /**
     * 给整份测试集打分（通常是各用例的平均）。
     *
     * <p>默认实现会跳过返回 {@code NaN} 的用例。这样负样本在排序类指标里
     * 既不会拉低分数，也不会虚高分数，而是干脆不计入。
     *
     * @return 平均值；如果所有用例都不参与计算，返回 0
     */
    default double scoreAll(EvaluationSet evaluationSet, List<RetrievedEvidence> evidences) {
        if (evidences.size() != evaluationSet.size()) {
            throw new IllegalArgumentException(
                    "召回结果数量(" + evidences.size() + ") 与测试集用例数("
                            + evaluationSet.size() + ") 不一致");
        }
        double sum = 0;
        int counted = 0;
        for (int i = 0; i < evaluationSet.size(); i++) {
            double s = score(evaluationSet.cases().get(i), evidences.get(i));
            if (!Double.isNaN(s)) {
                sum += s;
                counted++;
            }
        }
        return counted == 0 ? 0.0 : sum / counted;
    }
}
