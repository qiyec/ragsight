package cn.qiyec.ragsight.infra.retriever;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.retrieval.EmbeddingModel;
import cn.qiyec.ragsight.domain.retrieval.Retriever;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;
import cn.qiyec.ragsight.domain.retrieval.VectorIndex;

import java.util.List;

/**
 * 向量检索器：把 {@link EmbeddingModel} 与 {@link VectorIndex} 组合成 {@link Retriever}。
 *
 * <p>这是"组合优于继承"的具体体现——本类几乎没有逻辑，只负责编排：
 * 查询向量化 → 索引搜索 → 返回候选。真正的能力在 domain 定义的两个端口里。
 *
 * <p><b>为什么做索引构建的便捷入口</b>：如果让调用方自己依次调用
 * {@code embedding.embed(chunks)} 和 {@code index.rebuild(...)}，
 * 就有机会把两个列表的顺序搞乱、把维度传错。把这段编排收进来，
 * 让"构建一个可用的向量检索器"变成一步操作。
 */
public final class VectorRetriever implements Retriever {

    private final EmbeddingModel embeddingModel;
    private final VectorIndex index;

    public VectorRetriever(EmbeddingModel embeddingModel, VectorIndex index) {
        if (embeddingModel == null) {
            throw new IllegalArgumentException("embeddingModel 不能为 null");
        }
        if (index == null) {
            throw new IllegalArgumentException("index 不能为 null");
        }
        this.embeddingModel = embeddingModel;
        this.index = index;
    }

    /**
     * 用给定语料构建一个可立即使用的向量检索器。
     *
     * @param embeddingModel 向量化模型
     * @param index          向量索引（会被重建）
     * @param chunks         语料片段
     * @return 已建好索引的检索器
     */
    public static VectorRetriever indexed(EmbeddingModel embeddingModel, VectorIndex index,
                                          List<Chunk> chunks) {
        if (chunks == null) {
            throw new IllegalArgumentException("chunks 不能为 null");
        }
        if (chunks.isEmpty()) {
            // 空语料是合法输入（例如文档全是空白），此时索引为空，
            // 检索返回空结果。不视为错误，避免上层到处写判空分支。
            index.rebuild(List.of(), List.of(), embeddingModel.dimension());
            return new VectorRetriever(embeddingModel, index);
        }

        List<String> texts = chunks.stream().map(Chunk::text).toList();
        List<float[]> vectors = embeddingModel.embed(texts);
        index.rebuild(chunks, vectors, embeddingModel.dimension());
        return new VectorRetriever(embeddingModel, index);
    }

    @Override
    public List<ScoredChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，实际为 " + topK);
        }
        float[] queryVector = embeddingModel.embedOne(query);
        return index.search(queryVector, topK);
    }

    /** 检索器名称，用于诊断报告标识配置。 */
    public String name() {
        return "vector(" + embeddingModel.name() + ")";
    }

    /** 已索引片段数量。 */
    public int indexedSize() {
        return index.size();
    }

    public EmbeddingModel embeddingModel() {
        return embeddingModel;
    }
}
