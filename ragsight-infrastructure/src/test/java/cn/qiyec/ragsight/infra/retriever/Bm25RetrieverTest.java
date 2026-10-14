package cn.qiyec.ragsight.infra.retriever;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.retrieval.RetrievalSource;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;
import cn.qiyec.ragsight.infra.tokenizer.SimpleTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BM25 关键词检索器")
class Bm25RetrieverTest {

    private static Chunk chunk(String doc, int ordinal, String text) {
        ChunkId id = ChunkId.of(doc, ordinal);
        return new Chunk(id, id.documentId(), ordinal, text);
    }

    private static Bm25Retriever retrieverOf(List<Chunk> corpus) {
        return Bm25Retriever.indexed(new SimpleTokenizer(), corpus);
    }

    @Test
    @DisplayName("应按词元精确匹配召回最相关片段")
    void should_retrieve_by_exact_term_match() {
        List<Chunk> corpus = List.of(
                chunk("d", 0, "错误码 E4021 表示鉴权失败"),
                chunk("d", 1, "用户登录流程说明"),
                chunk("d", 2, "账单结算周期说明"));

        List<ScoredChunk> hits = retrieverOf(corpus).retrieve("E4021", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId().toString()).isEqualTo("d#0");
    }

    @Test
    @DisplayName("专有名词/编号检索是 BM25 相对向量的优势场景")
    void should_handle_proper_noun_query() {
        // 这类查询在向量空间里容易被语义相近的常见词淹没，
        // 而 BM25 基于精确词元匹配能稳定命中。
        List<Chunk> corpus = List.of(
                chunk("d", 0, "SKU-99812 商品下架操作指引"),
                chunk("d", 1, "商品管理功能总览"),
                chunk("d", 2, "下架流程的常见问题"));

        List<ScoredChunk> hits = retrieverOf(corpus).retrieve("SKU-99812", 3);

        assertThat(hits.get(0).chunkId().toString()).isEqualTo("d#0");
        assertThat(hits.get(0).score()).isGreaterThan(0);
    }

    @Test
    @DisplayName("结果来源应标记为 KEYWORD")
    void results_should_be_marked_as_keyword_source() {
        List<Chunk> corpus = List.of(chunk("d", 0, "计费规则说明"));

        assertThat(retrieverOf(corpus).retrieve("计费", 3))
                .allSatisfy(h -> assertThat(h.stage()).isEqualTo(RetrievalSource.KEYWORD));
    }

    @Test
    @DisplayName("rank 必须从 1 连续编号")
    void ranks_should_be_sequential_from_one() {
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费规则"),
                chunk("d", 1, "计费说明"),
                chunk("d", 2, "计费公告"));

        List<ScoredChunk> hits = retrieverOf(corpus).retrieve("计费", 3);

        assertThat(hits).hasSize(3);
        for (int i = 0; i < hits.size(); i++) {
            assertThat(hits.get(i).rank()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("结果片段必须来自原始语料，顺序与分数降序一致")
    void results_should_reference_original_chunks() {
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费规则"),
                chunk("d", 1, "登录流程"));

        List<ScoredChunk> hits = retrieverOf(corpus).retrieve("计费规则", 2);

        assertThat(hits.get(0).chunk().text()).isEqualTo("计费规则");
        assertThat(hits.get(0).score()).isGreaterThanOrEqualTo(hits.get(1).score());
    }

    @Test
    @DisplayName("topK 应截断结果")
    void should_respect_top_k() {
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费规则"),
                chunk("d", 1, "计费说明"),
                chunk("d", 2, "计费公告"));

        assertThat(retrieverOf(corpus).retrieve("计费", 2)).hasSize(2);
        assertThat(retrieverOf(corpus).retrieve("计费", 100)).hasSize(3);
    }

    @Test
    @DisplayName("空语料应被接受，检索返回空结果")
    void should_accept_empty_corpus() {
        Bm25Retriever retriever = retrieverOf(List.of());

        assertThat(retriever.indexedSize()).isZero();
        assertThat(retriever.retrieve("任意问题", 5)).isEmpty();
    }

    @Test
    @DisplayName("查询词与语料完全无交集时，零分片段仍被返回但排序确定")
    void should_return_zero_score_chunks_deterministically() {
        // 设计选择：无交集时仍返回片段（分数为 0），而不是过滤掉。
        //
        // 理由：诊断场景需要看到"检索器到底给出了什么"，即使是无关结果。
        // 负样本用例尤其需要——评测指标关心的是"正确答案是否出现在 topK"，
        // 若这里直接返回空会让指标计算与真实调用链不一致。
        // 分数为 0 已足以表达"无关"。
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费规则"),
                chunk("d", 1, "登录流程"));

        List<ScoredChunk> hits = retrieverOf(corpus).retrieve("完全不相关的词", 5);

        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(h -> assertThat(h.score()).isZero());
        // 同分时按语料下标升序，保证可复现
        assertThat(hits.get(0).chunkId().toString()).isEqualTo("d#0");
        assertThat(hits.get(1).chunkId().toString()).isEqualTo("d#1");
    }

    @Test
    @DisplayName("参数校验：空查询、非正 topK、null 依赖")
    void should_validate_parameters() {
        Bm25Retriever retriever = retrieverOf(List.of(chunk("d", 0, "计费规则")));

        assertThatThrownBy(() -> retriever.retrieve(null, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query 不能为空");
        assertThatThrownBy(() -> retriever.retrieve("  ", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query 不能为空");
        assertThatThrownBy(() -> retriever.retrieve("计费", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK 必须为正数");

        assertThatThrownBy(() -> Bm25Retriever.indexed(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenizer 不能为 null");
        assertThatThrownBy(() -> Bm25Retriever.indexed(new SimpleTokenizer(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunks 不能为 null");
    }

    @Test
    @DisplayName("可指定 BM25 参数，并能在名称中区分不同配置")
    void should_expose_bm25_parameters() {
        List<Chunk> corpus = List.of(chunk("d", 0, "计费规则"));

        Bm25Retriever custom = Bm25Retriever.indexed(new SimpleTokenizer(), corpus, 2.0, 0.5);

        assertThat(custom.k1()).isEqualTo(2.0);
        assertThat(custom.b()).isEqualTo(0.5);
        // 不同参数配置必须在名称上可区分，否则消融实验的报告无法标识
        assertThat(custom.name()).isNotEqualTo(retrieverOf(corpus).name());
        assertThat(custom.name()).contains("2.0").contains("0.5");
    }

    @Test
    @DisplayName("b=0 时（关闭长度归一化）长文档不再被惩罚")
    void b_zero_should_disable_length_penalty() {
        // 短文档 tf=1，长文档 tf=1 但包含大量填充词
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费"),
                chunk("d", 1, "计费 " + "填充 ".repeat(50)));

        Bm25Retriever noNorm = Bm25Retriever.indexed(new SimpleTokenizer(), corpus, 1.2, 0.0);
        Bm25Retriever fullNorm = Bm25Retriever.indexed(new SimpleTokenizer(), corpus, 1.2, 1.0);

        // b=0：长度项恒为 1，两篇同 tf 的文档分数相等
        assertThat(noNorm.retrieve("计费", 2).get(0).score())
                .isEqualTo(noNorm.retrieve("计费", 2).get(1).score());
        // b=1：长文档被惩罚，短文档分数更高
        assertThat(fullNorm.retrieve("计费", 2).get(0).chunkId().toString()).isEqualTo("d#0");
        assertThat(fullNorm.retrieve("计费", 2).get(0).score())
                .isGreaterThan(fullNorm.retrieve("计费", 2).get(1).score());
    }

    @Test
    @DisplayName("相同输入两次检索必须结果一致（可复现性，R5）")
    void should_be_deterministic() {
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费规则与账单说明"),
                chunk("d", 1, "计费规则的适用范围"),
                chunk("d", 2, "用户登录鉴权流程"));

        Bm25Retriever retriever = retrieverOf(corpus);

        List<String> first = retriever.retrieve("计费规则", 3)
                .stream().map(h -> h.chunkId().toString()).toList();
        List<String> second = retriever.retrieve("计费规则", 3)
                .stream().map(h -> h.chunkId().toString()).toList();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("分数无上界，与向量余弦不可比——这正是融合必须用排名的原因")
    void scores_are_unbounded_and_not_comparable_with_cosine() {
        // 构造一个反复出现查询词的文档，BM25 分数会明显超过 1
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费 计费 计费 计费 计费 计费 计费 计费"));

        double score = retrieverOf(corpus).retrieve("计费", 1).get(0).score();

        assertThat(score).isGreaterThan(1.0);
    }
}
