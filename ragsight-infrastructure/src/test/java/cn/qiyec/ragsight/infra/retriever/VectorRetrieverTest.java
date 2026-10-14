package cn.qiyec.ragsight.infra.retriever;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;
import cn.qiyec.ragsight.infra.embedding.DeterministicHashEmbedding;
import cn.qiyec.ragsight.infra.index.InMemoryVectorIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("向量检索器（端到端：向量化 → 索引 → 检索）")
class VectorRetrieverTest {

    private static Chunk chunk(String doc, int ordinal, String text) {
        ChunkId id = ChunkId.of(doc, ordinal);
        return new Chunk(id, id.documentId(), ordinal, text);
    }

    @Test
    @DisplayName("应召回与查询字面最相关的片段")
    void should_retrieve_most_lexically_relevant_chunk() {
        // 语料刻意设计成互不共享字符，使排序结果无歧义
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费规则与账单说明"),
                chunk("d", 1, "用户登录鉴权流程"),
                chunk("d", 2, "天气晴朗适合出行"));

        VectorRetriever retriever = VectorRetriever.indexed(
                new DeterministicHashEmbedding(256), new InMemoryVectorIndex(), corpus);

        List<ScoredChunk> hits = retriever.retrieve("计费规则", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId().toString()).isEqualTo("d#0");
    }

    @Test
    @DisplayName("indexed 应完成索引构建并可报告规模")
    void indexed_should_build_index() {
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费规则"),
                chunk("d", 1, "登录流程"));

        VectorRetriever retriever = VectorRetriever.indexed(
                new DeterministicHashEmbedding(256), new InMemoryVectorIndex(), corpus);

        assertThat(retriever.indexedSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("空语料应被接受，检索返回空结果")
    void should_accept_empty_corpus() {
        // 空语料是合法输入（文档全为空白的情况），不应让上层到处判空
        VectorRetriever retriever = VectorRetriever.indexed(
                new DeterministicHashEmbedding(256), new InMemoryVectorIndex(), List.of());

        assertThat(retriever.indexedSize()).isZero();
        assertThat(retriever.retrieve("任意问题", 5)).isEmpty();
    }

    @Test
    @DisplayName("topK 应截断结果")
    void should_respect_top_k() {
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费规则"),
                chunk("d", 1, "计费说明"),
                chunk("d", 2, "计费公告"));

        VectorRetriever retriever = VectorRetriever.indexed(
                new DeterministicHashEmbedding(256), new InMemoryVectorIndex(), corpus);

        assertThat(retriever.retrieve("计费", 2)).hasSize(2);
        assertThat(retriever.retrieve("计费", 100)).hasSize(3);
    }

    @Test
    @DisplayName("查询为空应报错")
    void should_reject_blank_query() {
        VectorRetriever retriever = VectorRetriever.indexed(
                new DeterministicHashEmbedding(256), new InMemoryVectorIndex(),
                List.of(chunk("d", 0, "计费规则")));

        assertThatThrownBy(() -> retriever.retrieve(null, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query 不能为空");
        assertThatThrownBy(() -> retriever.retrieve("   ", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query 不能为空");
    }

    @Test
    @DisplayName("topK 非正应报错")
    void should_reject_non_positive_top_k() {
        VectorRetriever retriever = VectorRetriever.indexed(
                new DeterministicHashEmbedding(256), new InMemoryVectorIndex(),
                List.of(chunk("d", 0, "计费规则")));

        assertThatThrownBy(() -> retriever.retrieve("计费", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK 必须为正数");
    }

    @Test
    @DisplayName("构造参数不得为 null")
    void should_reject_null_dependencies() {
        assertThatThrownBy(() -> new VectorRetriever(null, new InMemoryVectorIndex()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embeddingModel 不能为 null");

        assertThatThrownBy(() -> new VectorRetriever(new DeterministicHashEmbedding(256), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 不能为 null");
    }

    @Test
    @DisplayName("相同输入两次检索必须结果一致（可复现性，铁律 R5）")
    void should_be_deterministic() {
        List<Chunk> corpus = List.of(
                chunk("d", 0, "计费规则与账单说明"),
                chunk("d", 1, "计费规则的适用范围"),
                chunk("d", 2, "用户登录鉴权流程"));

        VectorRetriever retriever = VectorRetriever.indexed(
                new DeterministicHashEmbedding(256), new InMemoryVectorIndex(), corpus);

        List<String> first = retriever.retrieve("计费规则", 3)
                .stream().map(h -> h.chunkId().toString()).toList();
        List<String> second = retriever.retrieve("计费规则", 3)
                .stream().map(h -> h.chunkId().toString()).toList();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("名称应包含 embedding 标识，供诊断报告区分配置")
    void name_should_include_embedding() {
        VectorRetriever retriever = VectorRetriever.indexed(
                new DeterministicHashEmbedding(256), new InMemoryVectorIndex(), List.of());

        assertThat(retriever.name()).contains("vector").contains("256");
    }

    @Test
    @DisplayName("维度不同的 embedding 重建索引后仍应可检索（索引维度随模型变化）")
    void should_support_different_embedding_dimensions() {
        List<Chunk> corpus = List.of(chunk("d", 0, "计费规则"));

        VectorRetriever small = VectorRetriever.indexed(
                new DeterministicHashEmbedding(64), new InMemoryVectorIndex(), corpus);
        VectorRetriever large = VectorRetriever.indexed(
                new DeterministicHashEmbedding(512), new InMemoryVectorIndex(), corpus);

        assertThat(small.retrieve("计费规则", 1)).hasSize(1);
        assertThat(large.retrieve("计费规则", 1)).hasSize(1);
    }
}
