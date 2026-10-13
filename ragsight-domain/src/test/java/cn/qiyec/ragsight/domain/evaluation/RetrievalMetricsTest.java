package cn.qiyec.ragsight.domain.evaluation;

import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 指标的手算验证。
 *
 * <p>这些用例的期望值必须**人工推导**，不能由实现反推——
 * 否则测试只能证明"代码没变"，不能证明"算得对"。
 * 每个用例的注释里都写出了推导过程。
 */
@DisplayName("检索指标（手算验证）")
class RetrievalMetricsTest {

    private static ChunkId cid(String doc, int ordinal) {
        return ChunkId.of(doc, ordinal);
    }

    private static GoldenCase caseOf(String id, ChunkId... expected) {
        return new GoldenCase(id, "问题-" + id, Set.of(expected),
                java.util.Optional.empty(), java.util.Optional.empty());
    }

    // ---------- Recall@k ----------

    @Test
    @DisplayName("Recall@5：2 个期望片段命中 1 个 → 0.5")
    void recall_should_count_partial_hits() {
        // 期望 = {d#1, d#3}，共 2 个
        // 前 5 名 = {d#1, d#2, d#5}，其中命中 d#1 → 命中 1 个
        // Recall@5 = 1 / 2 = 0.5
        GoldenCase testCase = caseOf("t1", cid("d", 1), cid("d", 3));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2), cid("d", 5)));

        assertThat(new RecallAtK(5).score(testCase, evidence)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("Recall@k：k 之外命中不计入")
    void recall_should_respect_k_cutoff() {
        // 期望 = {d#9}；排名第 3 命中，但 k=2 截断后看不到 → 0.0
        GoldenCase testCase = caseOf("t1", cid("d", 9));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2), cid("d", 9)));

        assertThat(new RecallAtK(2).score(testCase, evidence)).isZero();
        assertThat(new RecallAtK(3).score(testCase, evidence)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Recall@k：全部命中 → 1.0")
    void recall_should_be_one_when_all_hit() {
        GoldenCase testCase = caseOf("t1", cid("d", 1), cid("d", 2));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 2), cid("d", 1)));

        assertThat(new RecallAtK(5).score(testCase, evidence)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Recall@k：负样本（期望为空）为满分 1.0")
    void recall_should_be_one_for_negative_case() {
        // 语义：没有该找的证据，就不存在"漏找"
        GoldenCase negative = caseOf("neg");
        RetrievedEvidence evidence = RetrievedEvidence.of(List.of(cid("d", 1)));

        assertThat(negative.isNegative()).isTrue();
        assertThat(new RecallAtK(5).score(negative, evidence)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Recall@k：无任何召回结果 → 0.0")
    void recall_should_be_zero_for_empty_evidence() {
        GoldenCase testCase = caseOf("t1", cid("d", 1));

        assertThat(new RecallAtK(5).score(testCase, RetrievedEvidence.empty())).isZero();
    }

    @Test
    @DisplayName("Recall@k：k 必须为正")
    void recall_should_reject_non_positive_k() {
        assertThatThrownBy(() -> new RecallAtK(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k 必须为正数");
    }

    // ---------- MRR@k ----------

    @Test
    @DisplayName("MRR@k：期望片段排第 1 → RR = 1.0")
    void mrr_should_be_one_when_first_rank() {
        GoldenCase testCase = caseOf("t1", cid("d", 1));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2)));

        assertThat(new MRR(5).score(testCase, evidence)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("MRR@k：期望片段排第 3 → RR = 1/3")
    void mrr_should_use_reciprocal_of_rank() {
        // 排名：1:d#7  2:d#8  3:d#9（期望）
        // RR = 1 / 3
        GoldenCase testCase = caseOf("t1", cid("d", 9));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 7), cid("d", 8), cid("d", 9)));

        assertThat(new MRR(5).score(testCase, evidence)).isEqualTo(1.0 / 3.0);
    }

    @Test
    @DisplayName("MRR@k：多个期望片段时取最优排名")
    void mrr_should_pick_best_rank_among_expected() {
        // 期望 = {d#9, d#2}；实际排名 1:d#7  2:d#2  3:d#9
        // 最优排名 = 2（d#2 在位置 2，好于 d#9 的位置 3）
        // RR = 1 / 2 = 0.5
        GoldenCase testCase = caseOf("t1", cid("d", 9), cid("d", 2));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 7), cid("d", 2), cid("d", 9)));

        assertThat(new MRR(5).score(testCase, evidence)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("MRR@k：未命中 → 0.0")
    void mrr_should_be_zero_when_missed() {
        GoldenCase testCase = caseOf("t1", cid("d", 99));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2)));

        assertThat(new MRR(5).score(testCase, evidence)).isZero();
    }

    @Test
    @DisplayName("MRR@k：k 截断之外的命中不计入")
    void mrr_should_respect_k_cutoff() {
        // 期望 d#9 在排名 3，但 k=2 → 视为未命中 → 0.0
        GoldenCase testCase = caseOf("t1", cid("d", 9));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2), cid("d", 9)));

        assertThat(new MRR(2).score(testCase, evidence)).isZero();
    }

    @Test
    @DisplayName("MRR@k：负样本返回 NaN，表示不参与平均")
    void mrr_should_return_nan_for_negative_case() {
        // 负样本只检验"是否硬凑答案"，对排序质量无意义，
        // 若计为 0 会错误地拉低 MRR
        GoldenCase negative = caseOf("neg");
        RetrievedEvidence evidence = RetrievedEvidence.empty();

        assertThat(new MRR(5).score(negative, evidence)).isNaN();
    }

    // ---------- scoreAll：负样本跳过逻辑 ----------

    @Test
    @DisplayName("scoreAll：MRR 应跳过负样本，只对正样本求平均")
    void score_all_should_skip_negative_cases_for_mrr() {
        // 用例：t1 命中排名 1（RR=1.0）；neg 为负样本（NaN，跳过）；t2 命中排名 2（RR=0.5）
        // 平均 = (1.0 + 0.5) / 2 = 0.75   —— 注意除以 2 而非 3
        EvaluationSet set = EvaluationSet.of("s", List.of(
                caseOf("t1", cid("d", 1)),
                caseOf("neg"),
                caseOf("t2", cid("d", 6))));

        List<RetrievedEvidence> evidences = List.of(
                RetrievedEvidence.of(List.of(cid("d", 1))),
                RetrievedEvidence.of(List.of(cid("d", 3))),
                RetrievedEvidence.of(List.of(cid("d", 5), cid("d", 6))));

        assertThat(new MRR(5).scoreAll(set, evidences)).isEqualTo(0.75);
    }

    @Test
    @DisplayName("scoreAll：Recall 对负样本计满分，应除以全部用例数")
    void score_all_should_count_negative_cases_for_recall() {
        // t1 满分 1.0；neg 按定义 1.0；t2 = 0.0（未命中）
        // 平均 = (1.0 + 1.0 + 0.0) / 3 = 0.666...
        EvaluationSet set = EvaluationSet.of("s", List.of(
                caseOf("t1", cid("d", 1)),
                caseOf("neg"),
                caseOf("t2", cid("d", 99))));

        List<RetrievedEvidence> evidences = List.of(
                RetrievedEvidence.of(List.of(cid("d", 1))),
                RetrievedEvidence.empty(),
                RetrievedEvidence.of(List.of(cid("d", 5))));

        assertThat(new RecallAtK(5).scoreAll(set, evidences))
                .isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    @DisplayName("scoreAll：结果数与用例数不一致时必须报错，而不是静默错配")
    void score_all_should_reject_size_mismatch() {
        EvaluationSet set = EvaluationSet.of("s", List.of(caseOf("t1", cid("d", 1))));

        assertThatThrownBy(() -> new RecallAtK(5).scoreAll(set, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不一致");
    }
}
