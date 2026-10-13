package cn.qiyec.ragsight.domain.evaluation;

import java.util.List;

/**
 * 评估指标（端口）。
 *
 * <p>所有指标都是**纯函数**：给定评测集与检索结果，输出唯一确定的分数。
 * 这是诊断结论可复现的前提——绝不能引入 LLM 或随机性（见 ADR-001、铁律 R5）。
 *
 * <p>设计上让实现自行处理负样本：负样本（期望集合为空）在"召回率"类指标中
 * 应视为满分（没有该找的东西，就没漏），而在"排序质量"类指标中应视为
 * 不参与计算。这个差异是语义性的，不该由调用方统一处理。
 */
public interface Metric {

    /** 指标名称，如 {@code "Recall@5"}。用于报告展示与结果比对。 */
    String name();

    /**
     * 对单个用例打分。
     *
     * @param testCase 标注用例
     * @param evidence 该用例的实际检索结果
     * @return 分数。召回类指标取值 [0,1]；排序类指标中负样本无定义时返回
     *         {@link Double#NaN} 表示"不参与平均"
     */
    double score(GoldenCase testCase, RetrievedEvidence evidence);

    /**
     * 对整份评测集打分（通常是各用例的算术平均）。
     *
     * <p>默认实现跳过返回 {@code NaN} 的用例。这解决了负样本在排序类指标中
     * 无定义的问题——既不让它拉低分数，也不让它虚高分数，而是干脆不计入。
     *
     * @return 平均值；若所有用例都不参与计算，返回 0
     */
    default double scoreAll(EvaluationSet evaluationSet, List<RetrievedEvidence> evidences) {
        if (evidences.size() != evaluationSet.size()) {
            throw new IllegalArgumentException(
                    "检索结果数量(" + evidences.size() + ") 与评测集用例数("
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
