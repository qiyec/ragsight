package cn.qiyec.ragsight.domain.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static cn.qiyec.ragsight.domain.retrieval.RetrievalFixtures.ids;
import static cn.qiyec.ragsight.domain.retrieval.RetrievalFixtures.route;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("RRF 倒数排名融合")
class ReciprocalRankFusionTest {

    // 手算基准：k=60 时
    //   第 1 名的贡献 = 1/(60+1) = 1/61 ≈ 0.016393
    //   第 2 名的贡献 = 1/(60+2) = 1/62 ≈ 0.016129
    //   第 3 名的贡献 = 1/(60+3) = 1/63 ≈ 0.015873
    private static final double R1 = 1.0 / 61.0;
    private static final double R2 = 1.0 / 62.0;
    private static final double R3 = 1.0 / 63.0;

    @Test
    @DisplayName("k 必须为正，权重必须为正")
    void should_reject_invalid_parameters() {
        assertThatThrownBy(() -> ReciprocalRankFusion.withK(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k 必须为正数");

        assertThatThrownBy(() -> new ReciprocalRankFusion(60,
                Map.of(RetrievalSource.VECTOR, 0.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("权重必须为正数");
    }

    @Test
    @DisplayName("两路都排第 1 的片段应获得两次满分贡献并居首")
    void should_accumulate_contributions_across_routes() {
        // 向量路：1:d#1  2:d#2
        // 关键词路：1:d#1  2:d#3
        // d#1 出现在两路的第 1 名 → 分数 = R1 + R1 ≈ 0.032787
        // d#2 只出现一次且第 2 名   → R2 ≈ 0.016129
        // d#3 只出现一次且第 2 名   → R2 ≈ 0.016129
        // 预期顺序：d#1, 然后 d#2 与 d#3 同分（按片段标识排序 → d#2 在前）
        List<ScoredChunk> fused = ReciprocalRankFusion.defaults().fuse(List.of(
                route("d", RetrievalSource.VECTOR, 1, 2),
                route("d", RetrievalSource.KEYWORD, 1, 3)), 10);

        assertThat(ids(fused)).containsExactly("d#1", "d#2", "d#3");
        assertThat(fused.get(0).score()).isCloseTo(R1 + R1, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(R2, within(1e-12));
    }

    @Test
    @DisplayName("同一片段在两路都排第 1，分数必须是单路的两倍（验证聚合键正确）")
    void should_merge_same_chunk_across_routes() {
        // 这是最易写错的点：若用 ScoredChunk（record equals 含 rank）作聚合键，
        // 同一片段的两次出现会被当成两个条目，分数不会累加。
        List<ScoredChunk> single = ReciprocalRankFusion.defaults()
                .fuse(List.of(route("d", RetrievalSource.VECTOR, 1)), 10);
        List<ScoredChunk> dual = ReciprocalRankFusion.defaults().fuse(List.of(
                route("d", RetrievalSource.VECTOR, 1),
                route("d", RetrievalSource.KEYWORD, 1)), 10);

        assertThat(dual).hasSize(1);
        assertThat(dual.get(0).score())
                .isCloseTo(single.get(0).score() * 2, within(1e-12));
    }

    @Test
    @DisplayName("融合后 rank 必须从 1 连续编号，且来源标记为 FUSION")
    void should_renumber_ranks_and_mark_stage() {
        List<ScoredChunk> fused = ReciprocalRankFusion.defaults().fuse(List.of(
                route("d", RetrievalSource.VECTOR, 1, 2, 3),
                route("d", RetrievalSource.KEYWORD, 4, 5)), 10);

        for (int i = 0; i < fused.size(); i++) {
            assertThat(fused.get(i).rank()).isEqualTo(i + 1);
            assertThat(fused.get(i).stage()).isEqualTo(RetrievalSource.FUSION);
        }
    }

    @Test
    @DisplayName("k 越大，排名差异被压得越平")
    void k_should_flatten_rank_difference() {
        // 比较「第1名」与「第5名」的分数比：k 越大，比值越接近 1
        List<ScoredChunk> smallK = ReciprocalRankFusion.withK(1).fuse(List.of(
                route("d", RetrievalSource.VECTOR, 1, 2, 3, 4, 5)), 10);
        List<ScoredChunk> largeK = ReciprocalRankFusion.withK(1000).fuse(List.of(
                route("d", RetrievalSource.VECTOR, 1, 2, 3, 4, 5)), 10);

        double ratioSmall = smallK.get(0).score() / smallK.get(4).score();
        double ratioLarge = largeK.get(0).score() / largeK.get(4).score();

        assertThat(ratioSmall).isGreaterThan(ratioLarge);
        // k=1 时第1名 1/2、第5名 1/6，比值应为 3.0
        assertThat(ratioSmall).isCloseTo(3.0, within(1e-9));
        // k=1000 时比值应非常接近 1
        assertThat(ratioLarge).isLessThan(1.01);
    }

    @Test
    @DisplayName("权重应生效：加权后的来源贡献按比例放大")
    void weights_should_scale_contributions() {
        // 关键词路权重 2.0，则其第 1 名贡献 = 2 * R1
        // d#1 仅出自关键词路 → 2*R1；d#9 仅出自向量路 → R1
        List<ScoredChunk> fused = new ReciprocalRankFusion(60,
                Map.of(RetrievalSource.KEYWORD, 2.0)).fuse(List.of(
                route("d", RetrievalSource.VECTOR, 9),
                route("d", RetrievalSource.KEYWORD, 1)), 10);

        assertThat(ids(fused)).containsExactly("d#1", "d#9");
        assertThat(fused.get(0).score()).isCloseTo(2 * R1, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(R1, within(1e-12));
    }

    @Test
    @DisplayName("topK 应截断结果")
    void should_respect_top_k() {
        List<ScoredChunk> fused = ReciprocalRankFusion.defaults().fuse(List.of(
                route("d", RetrievalSource.VECTOR, 1, 2, 3, 4, 5)), 3);

        assertThat(fused).hasSize(3);
        assertThat(ids(fused)).containsExactly("d#1", "d#2", "d#3");
    }

    @Test
    @DisplayName("空输入应返回空结果而不是报错")
    void should_return_empty_for_empty_input() {
        assertThat(ReciprocalRankFusion.defaults().fuse(List.of(), 5)).isEmpty();
        assertThat(ReciprocalRankFusion.defaults()
                .fuse(List.of(List.of()), 5)).isEmpty();
        assertThat(ReciprocalRankFusion.defaults()
                .fuse(java.util.Arrays.asList((List<ScoredChunk>) null), 5)).isEmpty();
    }

    @Test
    @DisplayName("topK 非正应报错")
    void should_reject_non_positive_top_k() {
        assertThatThrownBy(() -> ReciprocalRankFusion.defaults().fuse(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK 必须为正数");
    }

    @Test
    @DisplayName("相同输入必须产出完全相同顺序（可复现性）")
    void should_be_deterministic() {
        List<List<ScoredChunk>> input = List.of(
                route("d", RetrievalSource.VECTOR, 1, 2, 3),
                route("d", RetrievalSource.KEYWORD, 3, 1, 2));

        List<String> first = ids(ReciprocalRankFusion.defaults().fuse(input, 10));
        List<String> second = ids(ReciprocalRankFusion.defaults().fuse(input, 10));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("同分时顺序必须稳定可复现")
    void should_be_stable_on_ties() {
        // 构造两个片段在两路中互换位置，使其融合分数完全相同
        // 向量路：1:d#1  2:d#2      关键词路：1:d#2  2:d#1
        // d#1 = R1 + R2，d#2 = R2 + R1 → 同分
        List<ScoredChunk> fused = ReciprocalRankFusion.defaults().fuse(List.of(
                route("d", RetrievalSource.VECTOR, 1, 2),
                route("d", RetrievalSource.KEYWORD, 2, 1)), 10);

        assertThat(fused.get(0).score()).isCloseTo(fused.get(1).score(), within(1e-12));
        // 同分按片段标识排序 → d#1 在前（因为 "d#1" < "d#2"）
        assertThat(ids(fused)).containsExactly("d#1", "d#2");
    }
}
