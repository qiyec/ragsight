package cn.qiyec.ragsight.infra.retriever;

import cn.qiyec.ragsight.application.RetrieverConfig;
import cn.qiyec.ragsight.application.RetrieverFactory;
import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.retrieval.EmbeddingModel;
import cn.qiyec.ragsight.domain.retrieval.ReciprocalRankFusion;
import cn.qiyec.ragsight.domain.retrieval.Retriever;
import cn.qiyec.ragsight.domain.retrieval.Tokenizer;
import cn.qiyec.ragsight.domain.retrieval.VectorIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 检索器工厂实现——把 {@link RetrieverConfig} 翻译成可执行的检索管道。
 *
 * <p>这是外层的实现：应用层的接口在这里被接到
 * 具体实现（离线 embedding、内存索引、BM25、RRF）上。
 *
 * <p><b>为什么接收工厂函数而不是实例</b>：同一次诊断的多个配置各自需要
 * 独立的向量索引。若共用同一个索引实例，后一个配置会覆盖前一个的索引，
 * 导致实验结果互相污染——这类错误在小语料上不易察觉，却会让整个
 * 诊断结论失效。用 {@code Supplier} 强制每个配置获得全新索引。
 *
 * <h2>关于融合候选倍数（一个实测得出的工程结论）</h2>
 *
 * <p>在演示语料上实测发现：<b>RRF 融合的排序质量可能低于单路</b>
 * （向量 MRR 0.667 / 关键词 0.762 / 融合 0.690）。
 * 根因不是融合算法错，而是**候选池太小**：RRF 只能对"已进入候选集的片段"
 * 重新排序，若某片段在两路中都排在候选截断之外，融合阶段无法把它找回来。
 *
 * <p>因此 {@code candidateMultiplier} 是一个真实的工程旋钮：
 * 调大它可提升融合上限，代价是更多计算。本项目把它暴露为可配参数，
 * 而不是藏一个魔法数字——"融合前召回多少候选"本身就是值得实验的变量。
 */
public final class CompositeRetrieverFactory implements RetrieverFactory {

    /**
     * 融合前候选数相对 topK 的默认放大倍数。
     *
     * <p>取 6 而非更小的值，是因为实测显示 3 倍在小语料上已会限制融合效果。
     * 语料规模更大时该倍数应相应调大（或改为"召回全量后取前 N"）。
     */
    public static final int DEFAULT_CANDIDATE_MULTIPLIER = 6;

    private final Supplier<EmbeddingModel> embeddingModelSupplier;
    private final Supplier<VectorIndex> vectorIndexSupplier;
    private final Supplier<Tokenizer> tokenizerSupplier;
    private final List<Chunk> corpus;
    private final int candidateMultiplier;

    public CompositeRetrieverFactory(Supplier<EmbeddingModel> embeddingModelSupplier,
                                     Supplier<VectorIndex> vectorIndexSupplier,
                                     Supplier<Tokenizer> tokenizerSupplier,
                                     List<Chunk> corpus) {
        this(embeddingModelSupplier, vectorIndexSupplier, tokenizerSupplier, corpus,
                DEFAULT_CANDIDATE_MULTIPLIER);
    }

    public CompositeRetrieverFactory(Supplier<EmbeddingModel> embeddingModelSupplier,
                                     Supplier<VectorIndex> vectorIndexSupplier,
                                     Supplier<Tokenizer> tokenizerSupplier,
                                     List<Chunk> corpus,
                                     int candidateMultiplier) {
        if (embeddingModelSupplier == null) {
            throw new IllegalArgumentException("embeddingModelSupplier 不能为空");
        }
        if (vectorIndexSupplier == null) {
            throw new IllegalArgumentException("vectorIndexSupplier 不能为空");
        }
        if (tokenizerSupplier == null) {
            throw new IllegalArgumentException("tokenizerSupplier 不能为空");
        }
        if (corpus == null) {
            throw new IllegalArgumentException("corpus 不能为空");
        }
        if (candidateMultiplier <= 0) {
            throw new IllegalArgumentException(
                    "candidateMultiplier 必须为正数，实际为 " + candidateMultiplier);
        }
        this.embeddingModelSupplier = embeddingModelSupplier;
        this.vectorIndexSupplier = vectorIndexSupplier;
        this.tokenizerSupplier = tokenizerSupplier;
        this.corpus = List.copyOf(corpus);
        this.candidateMultiplier = candidateMultiplier;
    }

    @Override
    public Retriever create(RetrieverConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        return switch (config.kind()) {
            case VECTOR_ONLY -> buildVectorRetriever();
            case KEYWORD_ONLY -> buildKeywordRetriever();
            case HYBRID_RRF -> buildHybridRetriever(config.topK());
        };
    }

    private Retriever buildVectorRetriever() {
        return VectorRetriever.indexed(embeddingModelSupplier.get(), vectorIndexSupplier.get(), corpus);
    }

    private Retriever buildKeywordRetriever() {
        return Bm25Retriever.indexed(tokenizerSupplier.get(), corpus);
    }

    private Retriever buildHybridRetriever(int topK) {
        // 候选数不应超过语料总量，否则多出的召回没有意义（但也不会出错）
        int candidatesPerRoute = Math.min(topK * candidateMultiplier, Math.max(corpus.size(), topK));
        return new HybridRetriever(
                List.of(buildVectorRetriever(), buildKeywordRetriever()),
                ReciprocalRankFusion.defaults(),
                candidatesPerRoute);
    }

    /** 语料规模，便于报告展示与测试断言。 */
    public int corpusSize() {
        return corpus.size();
    }

    /** 融合前的候选倍数，便于报告与测试。 */
    public int candidateMultiplier() {
        return candidateMultiplier;
    }

    /**
     * 把片段列表按 id 去重，保留首次出现。
     *
     * <p>语料中出现重复 id 会让"按 id 比对期望片段"的评估失真——
     * 同一 id 对应多个不同片段时，指标无法判断召回的是哪一个。
     * 提供此工具让上层在构建语料时显式处理，而不是静默接受。
     *
     * @throws IllegalArgumentException 存在重复 id 时
     */
    public static List<Chunk> requireUniqueIds(List<Chunk> chunks) {
        Map<ChunkId, Chunk> unique = new LinkedHashMap<>();
        List<ChunkId> duplicates = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (unique.putIfAbsent(chunk.id(), chunk) != null) {
                duplicates.add(chunk.id());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "语料中存在重复的片段 id，会导致评估失真：" + duplicates);
        }
        return List.copyOf(unique.values());
    }
}
