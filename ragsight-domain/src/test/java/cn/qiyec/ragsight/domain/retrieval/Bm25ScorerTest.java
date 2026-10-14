package cn.qiyec.ragsight.domain.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * BM25 打分器的手算验证。
 *
 * <p>期望值全部由公式人工推导（下方注释给出完整过程），不由实现反推。
 * 若只把实现自己的输出当预期，测试只能证明"代码没变"，不能证明"算得对"。
 *
 * <pre>
 *   IDF(t) = ln(1 + (N - df + 0.5) / (df + 0.5))
 *   饱和项  = tf · (k1 + 1) / (tf + k1 · (1 - b + b · |d| / avgdl))
 *   score   = Σ IDF(t) · 饱和项
 * </pre>
 */
@DisplayName("BM25 打分器（手算验证）")
class Bm25ScorerTest {

    // ---------- 参数校验 ----------

    @Test
    @DisplayName("k1 为负应被拒绝")
    void should_reject_negative_k1() {
        assertThatThrownBy(() -> new Bm25Scorer(List.of(List.of("a")), -0.1, 0.75))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k1 必须 >= 0");
    }

    @Test
    @DisplayName("b 超出 [0,1] 应被拒绝")
    void should_reject_out_of_range_b() {
        assertThatThrownBy(() -> new Bm25Scorer(List.of(List.of("a")), 1.2, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("b 必须在 [0, 1]");

        assertThatThrownBy(() -> new Bm25Scorer(List.of(List.of("a")), 1.2, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("b 必须在 [0, 1]");
    }

    @Test
    @DisplayName("文档下标越界应被拒绝")
    void should_reject_out_of_range_document_index() {
        Bm25Scorer scorer = Bm25Scorer.withDefaults(List.of(List.of("a")));

        assertThatThrownBy(() -> scorer.score(List.of("a"), 1))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessageContaining("超出范围");
    }

    // ---------- 基础语义 ----------

    @Test
    @DisplayName("查询词完全不在语料中时，所有文档得分为 0")
    void should_score_zero_when_term_absent_everywhere() {
        // 若实现写成 (N - df + 0.5)/(df + 0.5) 而无 1+，
        // 这里 df=0 会得到 ln(N+0.5) > 0 —— 但词根本没出现过，
        // 不该产生分数。Lucene 写法配合代码里的 "tf == 0 则跳过" 保证结果为 0。
        Bm25Scorer scorer = Bm25Scorer.withDefaults(List.of(
                List.of("计费", "规则"),
                List.of("登录", "流程")));

        assertThat(scorer.score(List.of("不存在的词"), 0)).isZero();
        assertThat(scorer.score(List.of("不存在的词"), 1)).isZero();
    }

    @Test
    @DisplayName("空查询应得 0 分")
    void should_score_zero_for_empty_query() {
        Bm25Scorer scorer = Bm25Scorer.withDefaults(List.of(List.of("计费", "规则")));

        assertThat(scorer.score(List.of(), 0)).isZero();
    }

    @Test
    @DisplayName("查询词重复不应放大权重（BM25 假设查询词相互独立）")
    void duplicate_query_terms_should_not_multiply_score() {
        Bm25Scorer scorer = Bm25Scorer.withDefaults(List.of(
                List.of("计费", "规则"),
                List.of("登录", "流程")));

        double once = scorer.score(List.of("计费"), 0);
        double thrice = scorer.score(List.of("计费", "计费", "计费"), 0);

        assertThat(thrice).isCloseTo(once, within(1e-12));
    }

    // ---------- 手算核心用例 ----------

    /**
     * 语料（4 篇）：
     * <pre>
     *   docA: [词, 词]                 |d|=2
     *   docB: [词, 词, 词]             |d|=3
     *   docC: [其它, 其它]             |d|=2   不含"词"
     *   docD: [其它, 其它, 词]         |d|=3
     *   N = 4, avgdl = (2+3+2+3)/4 = 2.5, df(词) = 3
     *
     *   IDF = ln(1 + (4 - 3 + 0.5) / (3 + 0.5)) = ln(1 + 1.5/3.5) = ln(10/7)
     * </pre>
     */
    private static Bm25Scorer corpusOfFour() {
        return Bm25Scorer.withDefaults(List.of(
                List.of("词", "词"),
                List.of("词", "词", "词"),
                List.of("其它", "其它"),
                List.of("其它", "其它", "词")));
    }

    @Test
    @DisplayName("手算：IDF = ln(10/7) ≈ 0.356675")
    void idf_should_match_hand_calculation() {
        Bm25Scorer scorer = corpusOfFour();

        assertThat(scorer.documentCount()).isEqualTo(4);
        assertThat(scorer.averageDocLength()).isEqualTo(2.5);
        assertThat(scorer.inverseDocumentFrequency("词"))
                .isCloseTo(Math.log(10.0 / 7.0), within(1e-12));
        assertThat(Math.log(10.0 / 7.0)).isCloseTo(0.3566749439387324, within(1e-15));
    }

    @Test
    @DisplayName("手算：docA（tf=2, |d|=2）分数 = 0.519658858718683")
    void doc_a_score_should_match_hand_calculation() {
        // lengthRatio = 2 / 2.5 = 0.8
        // 分母   = 2 + 1.2 × (1 - 0.75 + 0.75 × 0.8) = 2 + 1.2 × 0.85 = 3.02
        // 饱和项 = 2 × 2.2 / 3.02 = 1.4569536423841060
        // score  = 0.3566749439387324 × 1.4569536423841060 = 0.519658858718683
        double expected = Math.log(10.0 / 7.0)
                * (2.0 * (1.2 + 1) / (2.0 + 1.2 * (1 - 0.75 + 0.75 * (2.0 / 2.5))));

        assertThat(corpusOfFour().score(List.of("词"), 0)).isCloseTo(expected, within(1e-12));
        // 精确值由实现与公式双向确认（曾因手算舍入写错为 0.519647）
        assertThat(expected).isCloseTo(0.519658858718683, within(1e-15));
    }

    @Test
    @DisplayName("手算：docB（tf=3, |d|=3）分数 = 0.537455394976172，高于 docA")
    void doc_b_score_should_match_hand_calculation() {
        // lengthRatio = 3 / 2.5 = 1.2
        // 分母   = 3 + 1.2 × (1 - 0.75 + 0.75 × 1.2) = 3 + 1.2 × 1.15 = 4.38
        // 饱和项 = 3 × 2.2 / 4.38 = 1.5068493150684932
        // score  = 0.3566749439387324 × 1.5068493150684932 = 0.537455394976172
        double expected = Math.log(10.0 / 7.0)
                * (3.0 * (1.2 + 1) / (3.0 + 1.2 * (1 - 0.75 + 0.75 * (3.0 / 2.5))));

        assertThat(corpusOfFour().score(List.of("词"), 1)).isCloseTo(expected, within(1e-12));

        // 关键语义：docB 的 tf（3）高于 docA（2），虽然它更长（受长度惩罚），
        // 但**净效果仍是 docB 分更高**。
        // 这说明"长文档一定吃亏"是错的直觉——长度惩罚只是减缓 tf 的收益，不是抵消它。
        assertThat(corpusOfFour().score(List.of("词"), 1))
                .isGreaterThan(corpusOfFour().score(List.of("词"), 0));
    }

    @Test
    @DisplayName("手算：docD（tf=1, |d|=3）分数 = 0.329699528010593，因长度归一化低于同 tf 的短文档")
    void doc_d_score_should_be_penalized_by_length() {
        // lengthRatio = 3 / 2.5 = 1.2
        // 分母   = 1 + 1.2 × 1.15 = 2.38
        // 饱和项 = 1 × 2.2 / 2.38 = 0.9243697478991597
        // score  = 0.3566749439387324 × 0.9243697478991597 = 0.329699528010593
        double expected = Math.log(10.0 / 7.0)
                * (1.0 * (1.2 + 1) / (1.0 + 1.2 * (1 - 0.75 + 0.75 * (3.0 / 2.5))));

        assertThat(corpusOfFour().score(List.of("词"), 3)).isCloseTo(expected, within(1e-12));

        // 同 tf=1 但文档长度为 1 时（avgdl=1），无长度惩罚，分数应更高
        Bm25Scorer shorterCorpus = Bm25Scorer.withDefaults(List.of(
                List.of("词"),
                List.of("其它"),
                List.of("其它"),
                List.of("其它")));
        assertThat(corpusOfFour().score(List.of("词"), 3))
                .isLessThan(shorterCorpus.score(List.of("词"), 0));
    }

    @Test
    @DisplayName("不含查询词的文档得分为 0")
    void doc_without_term_should_score_zero() {
        assertThat(corpusOfFour().score(List.of("词"), 2)).isZero();
    }

    // ---------- 参数的实际作用 ----------

    @Test
    @DisplayName("k1=0 时词频饱和项恒为 1，相同长度的文档分数只由 IDF 决定")
    void k1_zero_should_remove_term_frequency_effect() {
        // k1=0 时饱和项 = tf × 1 / (tf + 0) = 1，与 tf 无关。
        // 本次比较的两篇文档长度相同，故长度项也相同 → 分数相等。
        List<List<String>> docs = List.of(
                List.of("词"),
                List.of("词", "词"));
        Bm25Scorer scorer = new Bm25Scorer(docs, 0.0, 0.0);

        assertThat(scorer.score(List.of("词"), 0))
                .isCloseTo(scorer.score(List.of("词"), 1), within(1e-12));
    }

    @Test
    @DisplayName("k1 越大，高频词的收益越接近线性（饱和越弱）")
    void larger_k1_should_weaken_saturation() {
        // 实测比值（b=0，两篇长度对齐）：
        //   k1=0.1    → 1.0645
        //   k1=1.2    → 1.5714
        //   k1=1000   → 2.9940（趋近理论上限 3，即完全无饱和）
        double ratioSmallK = ratioOfTf3ToTf1(0.1);
        double ratioLargeK = ratioOfTf3ToTf1(1000.0);

        assertThat(ratioSmallK).isLessThan(ratioLargeK);
        // 小 k1 下 tf 的收益被强烈压制（比值接近 1）
        assertThat(ratioSmallK).isLessThan(1.1);
        // k1 极大时饱和项趋近 (k1+1)/k1 ≈ 1，比值趋近 tf 之比 3
        assertThat(ratioLargeK).isCloseTo(3.0, within(0.01));
    }

    private static double ratioOfTf3ToTf1(double k1) {
        // 两篇长度对齐的文档：tf=1 与 tf=3
        List<List<String>> docs = List.of(
                List.of("词", "填充"),
                List.of("词", "词", "词", "填充"));
        Bm25Scorer scorer = new Bm25Scorer(docs, k1, 0.0);
        return scorer.score(List.of("词"), 1) / scorer.score(List.of("词"), 0);
    }

    @Test
    @DisplayName("b=0 关闭长度归一化，b=1 完全归一化")
    void b_should_control_length_normalization() {
        // 短文档 tf=1 |d|=2；长文档 tf=1 |d|=20
        List<List<String>> docs = List.of(
                List.of("词", "填"),
                List.of("词", "填", "填", "填", "填", "填", "填", "填", "填", "填",
                        "填", "填", "填", "填", "填", "填", "填", "填", "填", "填"));

        Bm25Scorer noNorm = new Bm25Scorer(docs, 1.2, 0.0);
        Bm25Scorer fullNorm = new Bm25Scorer(docs, 1.2, 1.0);

        // b=0：长度项恒为 1 → 相同 tf 的两篇分数相等
        assertThat(noNorm.score(List.of("词"), 0))
                .isCloseTo(noNorm.score(List.of("词"), 1), within(1e-12));
        // b=1：长文档被惩罚 → 短文档分数严格更高
        assertThat(fullNorm.score(List.of("词"), 0))
                .isGreaterThan(fullNorm.score(List.of("词"), 1));
    }

    // ---------- 排序与确定性 ----------

    @Test
    @DisplayName("rankByScore 应按分数降序返回下标")
    void rank_should_order_by_score_descending() {
        // 实测分数：docB 0.5375 > docA 0.5197 > docD 0.3297 > docC 0
        // docB 虽更长（受长度惩罚），但 tf=3 的收益仍然胜出 —— 见 doc_b 测试的说明
        List<Integer> order = corpusOfFour().rankByScore(List.of("词"));

        assertThat(order).containsExactly(1, 0, 3, 2);
    }

    @Test
    @DisplayName("同分时按下标升序，保证结果确定可复现（R5）")
    void ties_should_break_by_index_ascending() {
        // 三篇完全相同的文档 → 分数相同 → 下标升序
        List<List<String>> docs = List.of(
                List.of("词"),
                List.of("词"),
                List.of("词"));
        Bm25Scorer scorer = Bm25Scorer.withDefaults(docs);

        List<Integer> order = scorer.rankByScore(List.of("词"));

        assertThat(order).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("相同输入两次打分必须完全一致（可复现性）")
    void should_be_deterministic() {
        Bm25Scorer scorer = corpusOfFour();

        assertThat(scorer.scoreAll(List.of("词")))
                .isEqualTo(corpusOfFour().scoreAll(List.of("词")));
    }

    @Test
    @DisplayName("空语料应被接受，打分返回空数组")
    void should_accept_empty_corpus() {
        Bm25Scorer scorer = Bm25Scorer.withDefaults(List.of());

        assertThat(scorer.documentCount()).isZero();
        assertThat(scorer.averageDocLength()).isZero();
        assertThat(scorer.scoreAll(List.of("词"))).isEmpty();
        assertThat(scorer.rankByScore(List.of("词"))).isEmpty();
    }

    @Test
    @DisplayName("多词查询的分数应为各词分数之和")
    void multi_term_score_should_be_sum_of_terms() {
        Bm25Scorer scorer = corpusOfFour();

        double onlyTerm = scorer.score(List.of("词"), 0);
        // "其它" 在 docA 中不存在，故加分应为 0
        double bothTerms = scorer.score(List.of("词", "其它"), 0);

        assertThat(bothTerms).isCloseTo(onlyTerm, within(1e-12));
    }
}
