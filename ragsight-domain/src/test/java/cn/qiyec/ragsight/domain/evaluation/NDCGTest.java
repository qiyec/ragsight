package cn.qiyec.ragsight.domain.evaluation;

import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * nDCG 的手算验证。
 *
 * <pre>
 *   DCG@k  = Σ rel(i) / log₂(i + 1)
 *   IDCG@k = Σ_{i=1..min(|期望|,k)} 1 / log₂(i + 1)
 *   nDCG@k = DCG@k / IDCG@k
 *
 *   折损因子：位置1 = 1/log₂2 = 1.000000
 *            位置2 = 1/log₂3 ≈ 0.630930
 *            位置3 = 1/log₂4 = 0.500000
 *            位置4 = 1/log₂5 ≈ 0.430677
 * </pre>
 */
@DisplayName("nDCG 指标（手算验证）")
class NDCGTest {

    private static final double D1 = 1.0 / (Math.log(2) / Math.log(2));       // 1.0
    private static final double D2 = 1.0 / (Math.log(3) / Math.log(2));       // 0.630930
    private static final double D3 = 1.0 / (Math.log(4) / Math.log(2));       // 0.5
    private static final double D4 = 1.0 / (Math.log(5) / Math.log(2));       // 0.430677

    private static ChunkId cid(String doc, int ordinal) {
        return ChunkId.of(doc, ordinal);
    }

    private static GoldenCase caseOf(String id, ChunkId... expected) {
        return new GoldenCase(id, "问题-" + id, Set.of(expected), Optional.empty(), Optional.empty());
    }

    private static GoldenCase negativeCase(String id) {
        return new GoldenCase(id, "问题-" + id, Set.of(), Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("k 必须为正")
    void should_reject_non_positive_k() {
        assertThatThrownBy(() -> new NDCG(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k 必须为正数");
    }

    @Test
    @DisplayName("理想排序（全部命中且在最前）应为 1.0")
    void perfect_ranking_should_score_one() {
        // 期望 2 个片段，实际排在前两位
        // DCG  = D1 + D2 = 1 + 0.630930 = 1.630930
        // IDCG = D1 + D2 = 1.630930   → nDCG = 1.0
        GoldenCase testCase = caseOf("t1", cid("d", 1), cid("d", 2));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2), cid("d", 9)));

        assertThat(new NDCG(5).score(testCase, evidence)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("手算：单期望片段排第 2 → nDCG = D2 ≈ 0.630930")
    void single_expected_at_rank_two() {
        // DCG  = D2 = 0.630930（位置2命中）
        // IDCG = D1 = 1.0        （理想情况排在位置1）
        // nDCG = 0.630930 / 1.0 = 0.630930
        GoldenCase testCase = caseOf("t1", cid("d", 9));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 9)));

        assertThat(new NDCG(5).score(testCase, evidence)).isCloseTo(D2, within(1e-12));
    }

    @Test
    @DisplayName("手算：两个期望片段分列第 1、3 位 → nDCG ≈ 0.939455")
    void two_expected_at_rank_one_and_three() {
        // DCG  = D1 + D3 = 1.0 + 0.5 = 1.5
        // IDCG = D1 + D2 = 1.0 + 0.630930 = 1.630930
        // nDCG = 1.5 / 1.630930 ≈ 0.919721
        GoldenCase testCase = caseOf("t1", cid("d", 1), cid("d", 5));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2), cid("d", 5)));

        double expected = (D1 + D3) / (D1 + D2);
        assertThat(new NDCG(5).score(testCase, evidence)).isCloseTo(expected, within(1e-12));
        assertThat(expected).isCloseTo(0.9197207, within(1e-6));
    }

    @Test
    @DisplayName("nDCG 对后续相关结果的位置敏感，而 MRR 不敏感——这是它存在的理由")
    void ndcg_should_be_sensitive_to_later_positions_unlike_mrr() {
        // 两种排序的 MRR 完全相同（第一个相关结果都在第 1 位），
        // 但第二个相关结果的位置不同 → nDCG 必须能区分它们
        GoldenCase testCase = caseOf("t1", cid("d", 1), cid("d", 5));

        RetrievedEvidence good = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 5), cid("d", 2)));
        RetrievedEvidence bad = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2), cid("d", 5)));

        assertThat(new MRR(5).score(testCase, good))
                .isEqualTo(new MRR(5).score(testCase, bad));

        assertThat(new NDCG(5).score(testCase, good))
                .isGreaterThan(new NDCG(5).score(testCase, bad));
    }

    @Test
    @DisplayName("完全未命中应为 0")
    void no_hits_should_score_zero() {
        GoldenCase testCase = caseOf("t1", cid("d", 99));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2)));

        assertThat(new NDCG(5).score(testCase, evidence)).isZero();
    }

    @Test
    @DisplayName("k 截断之外的命中不计入")
    void should_respect_k_cutoff() {
        GoldenCase testCase = caseOf("t1", cid("d", 9));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2), cid("d", 9)));

        assertThat(new NDCG(2).score(testCase, evidence)).isZero();
        assertThat(new NDCG(3).score(testCase, evidence)).isGreaterThan(0);
    }

    @Test
    @DisplayName("负样本返回 NaN，表示不参与平均")
    void negative_case_should_return_nan() {
        assertThat(new NDCG(5).score(negativeCase("neg"), RetrievedEvidence.empty())).isNaN();
    }

    @Test
    @DisplayName("期望片段数超过 k 时，IDCG 上限为 k（理想排序也只能放进 k 个）")
    void idcg_should_cap_at_k() {
        // 期望 5 个片段，k=2：理想情况只有 2 个能进入前 2
        // IDCG = D1 + D2
        // 实际：位置 1、2 都是期望片段 → DCG = D1 + D2 → nDCG = 1.0
        GoldenCase testCase = caseOf("t1",
                cid("d", 1), cid("d", 2), cid("d", 3), cid("d", 4), cid("d", 5));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2)));

        assertThat(new NDCG(2).score(testCase, evidence)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("scoreAll 应跳过负样本后求平均")
    void score_all_should_skip_negative_cases() {
        // t1 理想排序 → 1.0；neg 跳过；t2 命中位置 2 → D2
        // 平均 = (1.0 + D2) / 2
        EvaluationSet set = EvaluationSet.of("s", List.of(
                caseOf("t1", cid("d", 1)),
                negativeCase("neg"),
                caseOf("t2", cid("d", 6))));

        List<RetrievedEvidence> evidences = List.of(
                RetrievedEvidence.of(List.of(cid("d", 1))),
                RetrievedEvidence.empty(),
                RetrievedEvidence.of(List.of(cid("d", 5), cid("d", 6))));

        double expected = (1.0 + D2) / 2;
        assertThat(new NDCG(5).scoreAll(set, evidences)).isCloseTo(expected, within(1e-12));
    }

    @Test
    @DisplayName("relevantPositions 应返回命中位置，供报告解释分数成因")
    void relevant_positions_should_be_reported() {
        GoldenCase testCase = caseOf("t1", cid("d", 1), cid("d", 5));
        RetrievedEvidence evidence = RetrievedEvidence.of(
                List.of(cid("d", 1), cid("d", 2), cid("d", 5), cid("d", 3)));

        assertThat(new NDCG(5).relevantPositions(testCase, evidence)).containsExactly(1, 3);
    }

    @Test
    @DisplayName("折损因子数值确认（防止公式写错）")
    void discount_factors_should_match_formula() {
        // 位置 1 不折损
        double p1 = 1.0 / (Math.log(1 + 1) / Math.log(2));
        // 位置 2
        double p2 = 1.0 / (Math.log(2 + 1) / Math.log(2));
        // 位置 3
        double p3 = 1.0 / (Math.log(3 + 1) / Math.log(2));

        assertThat(p1).isCloseTo(D1, within(1e-12));
        assertThat(p2).isCloseTo(D2, within(1e-12));
        assertThat(p3).isCloseTo(D3, within(1e-12));
        assertThat(D1).isCloseTo(1.0, within(1e-12));
        assertThat(D2).isCloseTo(0.6309297535714574, within(1e-15));
        assertThat(D3).isCloseTo(0.5, within(1e-15));
        assertThat(D4).isCloseTo(0.43067655807339306, within(1e-15));
    }
}
