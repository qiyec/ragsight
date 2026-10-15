package cn.qiyec.ragsight.domain.retrieval;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 字面重合打分的手算验证。
 *
 * <p>算法：命中数 / 候选片段词数。
 * 分母用片段词数而不是查询词数，这样长片段会被自然压下去——
 * 长片段只要包含全部查询词就能拿满分的话，上下文里会塞进大量无关内容。
 */
@DisplayName("字面重合打分（手算验证）")
class LexicalOverlapScorerTest {

    /** 测试用的简易分词器：按空白切。 */
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

    private static ScoredChunk candidate(String docId, int ordinal, String text) {
        ChunkId id = ChunkId.of(docId, ordinal);
        return new ScoredChunk(new Chunk(id, id.documentId(), ordinal, text),
                1.0, ordinal + 1, RetrievalSource.FUSION);
    }

    @Test
    @DisplayName("手算：2 个词命中，片段共 4 个词 → 0.5")
    void score_should_be_hit_ratio() {
        // 查询词：a b；片段词：a b c d
        // 命中 a、b 两个 → 2 / 4 = 0.5
        var scorer = new LexicalOverlapScorer(List.of("a", "b"), WHITESPACE);
        var candidates = List.of(candidate("d", 0, "a b c d"));

        assertThat(scorer.score("a b", candidates)[0]).isEqualTo(0.5);
    }

    @Test
    @DisplayName("全部命中且片段没有多余词 → 1.0")
    void full_overlap_should_score_one() {
        var scorer = new LexicalOverlapScorer(List.of("a", "b"), WHITESPACE);
        var candidates = List.of(candidate("d", 0, "a b"));

        assertThat(scorer.score("a b", candidates)[0]).isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("一个词都没命中 → 0")
    void no_overlap_should_score_zero() {
        var scorer = new LexicalOverlapScorer(List.of("a", "b"), WHITESPACE);
        var candidates = List.of(candidate("d", 0, "x y z"));

        assertThat(scorer.score("a b", candidates)[0]).isZero();
    }

    @Test
    @DisplayName("同样命中 2 个词，片段越长分数越低——这是刻意的")
    void longer_chunk_should_score_lower_for_same_hits() {
        // 这篇只关心"同样命中数下长短片段怎么比"
        var scorer = new LexicalOverlapScorer(List.of("a", "b"), WHITESPACE);
        var candidates = List.of(
                candidate("d", 0, "a b"),                 // 2/2 = 1.0
                candidate("d", 1, "a b c d"),             // 2/4 = 0.5
                candidate("d", 2, "a b c d e f g h"));    // 2/8 = 0.25

        double[] scores = scorer.score("a b", candidates);

        assertThat(scores[0]).isGreaterThan(scores[1]);
        assertThat(scores[1]).isGreaterThan(scores[2]);
        assertThat(scores[2]).isCloseTo(0.25, within(1e-12));
    }

    @Test
    @DisplayName("查询词重复不应重复加分")
    void duplicate_query_terms_should_not_multiply_score() {
        // 「a a a」和「a」应当得到同样的分数。
        // 不去重的话，用户多打几个字就能把分数刷上去。
        var candidates = List.of(candidate("d", 0, "a b c d"));

        double once = new LexicalOverlapScorer(List.of("a"), WHITESPACE)
                .score("a", candidates)[0];
        double thrice = new LexicalOverlapScorer(List.of("a", "a", "a"), WHITESPACE)
                .score("a a a", candidates)[0];

        assertThat(thrice).isEqualTo(once);
    }

    /** 测试用的第二套分词器：只认字母，其余（含标点）一律丢掉。 */
    private static final Tokenizer LETTERS_ONLY = new Tokenizer() {
        @Override
        public String name() {
            return "letters-only";
        }

        @Override
        public List<String> tokenize(String text) {
            if (text == null || text.isBlank()) {
                return List.of();
            }
            List<String> tokens = new java.util.ArrayList<>();
            for (String part : text.trim().split("\\s+")) {
                if (part.chars().allMatch(Character::isLetter)) {
                    tokens.add(part);
                }
            }
            return tokens;
        }
    };

    @Test
    @DisplayName("片段切不出词、或查询为空时 → 0，不产生 NaN")
    void empty_inputs_should_score_zero() {
        // NaN 会污染排序和后续指标计算，必须避开
        var scorer = new LexicalOverlapScorer(List.of("a"), LETTERS_ONLY);
        // 这个片段非空白（Chunk 不允许空正文），但切不出任何词
        var punctuationOnly = List.of(candidate("d", 0, "... !!!"));

        double score = scorer.score("a", punctuationOnly)[0];
        assertThat(score).isZero();
        assertThat(Double.isNaN(score)).isFalse();

        // 查询为空时，任何片段都是 0 分
        var noQuery = new LexicalOverlapScorer(List.of(), LETTERS_ONLY);
        assertThat(noQuery.score("", List.of(candidate("d", 0, "a b")))[0]).isZero();
    }

    @Test
    @DisplayName("分数要和传入候选一一对应、顺序一致")
    void scores_should_align_with_candidates() {
        var scorer = new LexicalOverlapScorer(List.of("a"), WHITESPACE);
        var candidates = List.of(
                candidate("d", 0, "x y"),   // 0 命中
                candidate("d", 1, "a y"));  // 1/2 = 0.5

        double[] scores = scorer.score("a", candidates);

        assertThat(scores).hasSize(2);
        assertThat(scores[0]).isZero();
        assertThat(scores[1]).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("构造参数不得为空")
    void should_reject_null_dependencies() {
        assertThatThrownBy(() -> new LexicalOverlapScorer(null, WHITESPACE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryTokens 不能为 null");

        assertThatThrownBy(() -> new LexicalOverlapScorer(List.of("a"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenizer 不能为 null");
    }

    @Test
    @DisplayName("相同输入两次打分必须一致（可复现）")
    void should_be_deterministic() {
        var candidates = List.of(
                candidate("d", 0, "a b c"),
                candidate("d", 1, "a b c d e"));

        var scorer = new LexicalOverlapScorer(List.of("a", "b"), WHITESPACE);

        assertThat(scorer.score("a b", candidates))
                .isEqualTo(scorer.score("a b", candidates));
    }

    @Test
    @DisplayName("名称应能区分用了哪个分词器")
    void name_should_include_tokenizer() {
        assertThat(new LexicalOverlapScorer(List.of("a"), WHITESPACE).name())
                .contains("whitespace");
    }
}
