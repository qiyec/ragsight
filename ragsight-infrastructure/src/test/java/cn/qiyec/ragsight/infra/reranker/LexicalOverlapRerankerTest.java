package cn.qiyec.ragsight.infra.reranker;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.retrieval.RetrievalSource;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;
import cn.qiyec.ragsight.domain.retrieval.Tokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("字面重合重排")
class LexicalOverlapRerankerTest {

    /** 简易分词器：按空白切。 */
    private static final Tokenizer WHITESPACE = new Tokenizer() {
        @Override
        public String name() {
            return "whitespace";
        }

        @Override
        public List<String> tokenize(String text) {
            return text.isBlank() ? List.of() : Arrays.asList(text.trim().split("\\s+"));
        }
    };

    private static ScoredChunk candidate(String docId, int ordinal, String text, int rank) {
        ChunkId id = ChunkId.of(docId, ordinal);
        return new ScoredChunk(new Chunk(id, id.documentId(), ordinal, text),
                1.0, rank, RetrievalSource.VECTOR);
    }

    @Test
    @DisplayName("应把字面重合度高的候选提到前面")
    void should_promote_better_matching_candidate() {
        // 召回阶段把最匹配的排在了最后（rank 3），重排应该把它提到第 1
        var candidates = List.of(
                candidate("d", 0, "x y z", 1),
                candidate("d", 1, "a y z", 2),
                candidate("d", 2, "a b", 3));

        var reranked = new LexicalOverlapReranker(WHITESPACE)
                .rerank("a b", candidates, 3);

        assertThat(reranked).extracting(r -> r.chunkId().toString())
                .containsExactly("d#2", "d#1", "d#0");
        assertThat(reranked.get(0).rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("重排结果的来源应标记为 RERANK")
    void results_should_be_marked_as_rerank_source() {
        var candidates = List.of(candidate("d", 0, "a b", 1));

        var reranked = new LexicalOverlapReranker(WHITESPACE).rerank("a", candidates, 5);

        assertThat(reranked).allSatisfy(
                r -> assertThat(r.stage()).isEqualTo(RetrievalSource.RERANK));
    }

    @Test
    @DisplayName("rank 应重新从 1 连续编号")
    void ranks_should_be_renumbered() {
        var candidates = List.of(
                candidate("d", 0, "a", 5),
                candidate("d", 1, "a b", 9),
                candidate("d", 2, "a b c", 2));

        var reranked = new LexicalOverlapReranker(WHITESPACE).rerank("a", candidates, 3);

        for (int i = 0; i < reranked.size(); i++) {
            assertThat(reranked.get(i).rank()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("topN 应截断结果，且只留分数最高的那些")
    void should_respect_top_n() {
        var candidates = List.of(
                candidate("d", 0, "x y z", 1),   // 0 命中
                candidate("d", 1, "a b c", 2),   // 1/3
                candidate("d", 2, "a b", 3));    // 2/2 = 1.0

        var reranked = new LexicalOverlapReranker(WHITESPACE).rerank("a b", candidates, 2);

        assertThat(reranked).hasSize(2);
        assertThat(reranked).extracting(r -> r.chunkId().toString())
                .containsExactly("d#2", "d#1");
    }

    @Test
    @DisplayName("同分时按片段标识排序，保证结果可复现")
    void ties_should_break_deterministically() {
        // 两篇完全对称的候选，分数一样，只能靠次级键定顺序
        var candidates = List.of(
                candidate("d", 1, "a x", 1),
                candidate("d", 2, "a y", 2));

        var reranked = new LexicalOverlapReranker(WHITESPACE).rerank("a", candidates, 5);

        assertThat(reranked).extracting(r -> r.chunkId().toString())
                .containsExactly("d#1", "d#2");
    }

    @Test
    @DisplayName("空候选应返回空结果，不报错")
    void empty_candidates_should_return_empty() {
        assertThat(new LexicalOverlapReranker(WHITESPACE).rerank("a", List.of(), 5)).isEmpty();
    }

    @Test
    @DisplayName("参数校验：空查询、非正 topN、null 分词器")
    void should_validate_parameters() {
        var reranker = new LexicalOverlapReranker(WHITESPACE);
        var candidates = List.of(candidate("d", 0, "a", 1));

        assertThatThrownBy(() -> reranker.rerank(null, candidates, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query 不能为空");
        assertThatThrownBy(() -> reranker.rerank("  ", candidates, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query 不能为空");
        assertThatThrownBy(() -> reranker.rerank("a", candidates, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topN 必须为正数");
        assertThatThrownBy(() -> new LexicalOverlapReranker(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenizer 不能为 null");
    }

    @Test
    @DisplayName("相同输入两次重排结果必须一致")
    void should_be_deterministic() {
        var candidates = List.of(
                candidate("d", 0, "a b c", 1),
                candidate("d", 1, "a b", 2),
                candidate("d", 2, "a", 3));

        var reranker = new LexicalOverlapReranker(WHITESPACE);

        assertThat(reranker.rerank("a b", candidates, 3).stream()
                .map(r -> r.chunkId().toString()).toList())
                .isEqualTo(reranker.rerank("a b", candidates, 3).stream()
                        .map(r -> r.chunkId().toString()).toList());
    }

    @Test
    @DisplayName("名称应说明用的是哪种重排")
    void name_should_describe_the_method() {
        assertThat(new LexicalOverlapReranker(WHITESPACE).name())
                .contains("字面重合").contains("whitespace");
    }
}
