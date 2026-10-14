package cn.qiyec.ragsight.infra.index;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.retrieval.RetrievalSource;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("内存向量索引")
class InMemoryVectorIndexTest {

    private final InMemoryVectorIndex index = new InMemoryVectorIndex();

    private static Chunk chunk(String doc, int ordinal) {
        ChunkId id = ChunkId.of(doc, ordinal);
        return new Chunk(id, id.documentId(), ordinal, "正文-" + id);
    }

    /** 构造一个只有指定维度非零的向量，便于精确控制相似度。 */
    private static float[] basis(int dimension, int nonZeroAt) {
        float[] v = new float[dimension];
        v[nonZeroAt] = 1.0f;
        return v;
    }

    @Test
    @DisplayName("尚未建立索引时搜索应返回空列表，而不是报错")
    void should_return_empty_before_rebuild() {
        // 空知识库是合法场景，不应让上层到处写判空分支
        assertThat(index.size()).isZero();
        assertThat(index.dimension()).isZero();
        assertThat(index.search(new float[]{1.0f, 0.0f}, 5)).isEmpty();
    }

    @Test
    @DisplayName("rebuild 后应能报告片段数与维度")
    void should_report_size_and_dimension_after_rebuild() {
        index.rebuild(
                List.of(chunk("d", 0), chunk("d", 1)),
                List.of(basis(3, 0), basis(3, 1)),
                3);

        assertThat(index.size()).isEqualTo(2);
        assertThat(index.dimension()).isEqualTo(3);
    }

    @Test
    @DisplayName("rebuild 使用替换语义，避免上一轮实验的数据污染本轮")
    void rebuild_should_replace_not_append() {
        index.rebuild(List.of(chunk("d", 0)), List.of(basis(3, 0)), 3);
        index.rebuild(List.of(chunk("e", 0), chunk("e", 1)), List.of(basis(3, 1), basis(3, 2)), 3);

        assertThat(index.size()).isEqualTo(2);
        List<ScoredChunk> hits = index.search(basis(3, 1), 5);
        assertThat(hits).hasSize(2);
        // 旧的 d#0 不应再出现在结果中
        assertThat(hits).allSatisfy(h -> assertThat(h.chunkId().documentId().value()).isEqualTo("e"));
    }

    @Test
    @DisplayName("应按相似度降序返回结果，且 rank 从 1 连续编号")
    void should_rank_by_similarity_descending() {
        // 查询与第 0 维对齐：vec0 完全一致（相似度 1.0），vec1 正交（0.0）
        index.rebuild(
                List.of(chunk("d", 0), chunk("d", 1)),
                List.of(basis(3, 0), basis(3, 1)),
                3);

        List<ScoredChunk> hits = index.search(basis(3, 0), 5);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).chunkId().toString()).isEqualTo("d#0");
        assertThat(hits.get(0).score()).isCloseTo(1.0, within(1e-9));
        assertThat(hits.get(1).chunkId().toString()).isEqualTo("d#1");
        assertThat(hits.get(1).score()).isCloseTo(0.0, within(1e-9));
        assertThat(hits.get(0).rank()).isEqualTo(1);
        assertThat(hits.get(1).rank()).isEqualTo(2);
    }

    @Test
    @DisplayName("结果来源应标记为 VECTOR")
    void results_should_be_marked_as_vector_source() {
        index.rebuild(List.of(chunk("d", 0)), List.of(basis(3, 0)), 3);

        assertThat(index.search(basis(3, 0), 5))
                .allSatisfy(h -> assertThat(h.stage()).isEqualTo(RetrievalSource.VECTOR));
    }

    @Test
    @DisplayName("topK 应截断结果")
    void should_respect_top_k() {
        index.rebuild(
                List.of(chunk("d", 0), chunk("d", 1), chunk("d", 2)),
                List.of(basis(3, 0), basis(3, 1), basis(3, 2)),
                3);

        assertThat(index.search(basis(3, 0), 2)).hasSize(2);
        assertThat(index.search(basis(3, 0), 100)).hasSize(3);
    }

    @Test
    @DisplayName("chunks 与 vectors 数量不一致应报错，而不是静默错位")
    void should_reject_size_mismatch() {
        // 这是最危险的静默错误：向量与片段错位后，
        // 检索结果看起来正常，但语义完全错乱。
        assertThatThrownBy(() -> index.rebuild(
                List.of(chunk("d", 0), chunk("d", 1)),
                List.of(basis(3, 0)),
                3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数量不一致");
    }

    @Test
    @DisplayName("向量维度与声明维度不符应报错")
    void should_reject_dimension_mismatch_on_rebuild() {
        assertThatThrownBy(() -> index.rebuild(
                List.of(chunk("d", 0)),
                List.of(basis(4, 0)),
                3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("与声明的维度");
    }

    @Test
    @DisplayName("查询向量维度与索引不符应报错，并提示可能原因")
    void should_reject_dimension_mismatch_on_search() {
        index.rebuild(List.of(chunk("d", 0)), List.of(basis(3, 0)), 3);

        assertThatThrownBy(() -> index.search(basis(4, 0), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("忘记重建索引");
    }

    @Test
    @DisplayName("topK 非正应报错")
    void should_reject_non_positive_top_k() {
        index.rebuild(List.of(chunk("d", 0)), List.of(basis(3, 0)), 3);

        assertThatThrownBy(() -> index.search(basis(3, 0), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK 必须为正数");
    }

    @Test
    @DisplayName("零向量查询不应产生 NaN，相似度应为 0")
    void zero_vector_should_yield_zero_similarity_not_nan() {
        // NaN 会污染排序与后续指标计算，必须避免
        index.rebuild(List.of(chunk("d", 0)), List.of(basis(3, 0)), 3);

        List<ScoredChunk> hits = index.search(new float[3], 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).isEqualTo(0.0);
        assertThat(Double.isNaN(hits.get(0).score())).isFalse();
    }

    @Test
    @DisplayName("同分时顺序必须稳定可复现（R5）")
    void should_be_stable_on_ties() {
        // 两个向量都与查询正交 → 相似度都是 0，排序只能靠次级键（片段标识）
        index.rebuild(
                List.of(chunk("d", 2), chunk("d", 1)),
                List.of(basis(3, 1), basis(3, 2)),
                3);

        List<String> first = index.search(basis(3, 0), 5)
                .stream().map(h -> h.chunkId().toString()).toList();
        List<String> second = index.search(basis(3, 0), 5)
                .stream().map(h -> h.chunkId().toString()).toList();

        assertThat(first).isEqualTo(second);
        // 同分按 ChunkId.toString() 排序，故 d#1 在 d#2 前
        assertThat(first).containsExactly("d#1", "d#2");
    }

    @Test
    @DisplayName("未归一化向量也必须得到正确的余弦相似度")
    void should_handle_unnormalized_vectors() {
        // 不依赖"调用方已归一化"这一隐含前提：
        // 依赖隐含前提的代码是维护陷阱，真实模型未必归一化。
        float[] longA = {10.0f, 0.0f};
        float[] shortA = {0.5f, 0.0f};

        index.rebuild(List.of(chunk("d", 0)), List.of(longA), 2);

        // 方向相同但模长差异巨大，余弦仍应为 1.0
        assertThat(index.search(shortA, 1).get(0).score()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("空索引 rebuild 应被接受，检索返回空")
    void should_accept_empty_rebuild() {
        index.rebuild(List.of(), List.of(), 3);

        assertThat(index.size()).isZero();
        assertThat(index.dimension()).isEqualTo(3);
        assertThat(index.search(basis(3, 0), 5)).isEmpty();
    }
}
