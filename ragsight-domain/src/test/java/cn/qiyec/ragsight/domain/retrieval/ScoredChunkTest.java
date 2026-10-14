package cn.qiyec.ragsight.domain.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScoredChunk 数据类")
class ScoredChunkTest {

    @Test
    @DisplayName("rank 从 1 开始，非法值应立即失败")
    void should_reject_invalid_rank() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> RetrievalFixtures.hit("d", 1, 0.5, 0, RetrievalSource.VECTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rank 从 1 开始");
    }

    @Test
    @DisplayName("score 为 NaN 应被拒绝，避免污染后续排序与平均值")
    void should_reject_nan_score() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> RetrievalFixtures.hit("d", 1, Double.NaN, 1, RetrievalSource.VECTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为 NaN");
    }

    @Test
    @DisplayName("rank 不同则两个 ScoredChunk 不相等——这正是 RRF 不能直接用它作聚合键的原因")
    void equality_should_include_rank() {
        ScoredChunk rankOne = RetrievalFixtures.hit("d", 1, 0.9, 1, RetrievalSource.VECTOR);
        ScoredChunk rankTwo = RetrievalFixtures.hit("d", 1, 0.9, 2, RetrievalSource.VECTOR);

        assertThat(rankOne).isNotEqualTo(rankTwo);
        // 但片段标识相同，聚合时应按标识而非对象相等来判断
        assertThat(rankOne.chunkId()).isEqualTo(rankTwo.chunkId());
    }

    @Test
    @DisplayName("withRank 应生成新对象而不修改原对象（不可变性）")
    void with_rank_should_return_new_instance() {
        ScoredChunk original = RetrievalFixtures.hit("d", 1, 0.9, 1, RetrievalSource.VECTOR);
        ScoredChunk moved = original.withRank(5);

        assertThat(original.rank()).isEqualTo(1);
        assertThat(moved.rank()).isEqualTo(5);
        assertThat(moved.chunkId()).isEqualTo(original.chunkId());
    }

    @Test
    @DisplayName("withStage 应保留分值与排名，仅改来源")
    void with_stage_should_only_change_stage() {
        ScoredChunk original = RetrievalFixtures.hit("d", 1, 0.9, 2, RetrievalSource.VECTOR);
        ScoredChunk reranked = original.withStage(RetrievalSource.RERANK);

        assertThat(reranked.stage()).isEqualTo(RetrievalSource.RERANK);
        assertThat(reranked.score()).isEqualTo(original.score());
        assertThat(reranked.rank()).isEqualTo(original.rank());
    }

    @Test
    @DisplayName("RetrievalSource 每个枚举值都应有中文标签")
    void sources_should_have_labels() {
        List<String> labels = new ArrayList<>();
        for (RetrievalSource s : RetrievalSource.values()) {
            assertThat(s.label()).isNotBlank();
            labels.add(s.label());
        }
        // 标签不应重复，否则报告里无法区分阶段
        assertThat(labels).doesNotHaveDuplicates();
    }
}
