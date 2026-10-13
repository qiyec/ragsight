package cn.qiyec.ragsight.domain.evaluation;

import cn.qiyec.ragsight.domain.knowledge.ChunkId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 一次检索实际召回的证据。
 *
 * <p>刻意只保留「片段标识 + 排名」两个信息，丢弃分值与正文。
 * 原因：指标计算只依赖**顺序**，不依赖分值——分值是不同检索器之间
 * 不可比的（向量余弦相似度和 BM25 分数完全不是一个量纲）。
 * 把分值排除在评估模型之外，可以从结构上防止"拿分值算指标"这类错误。
 *
 * @param hits 按排名从 1 开始的命中列表
 */
public record RetrievedEvidence(List<Hit> hits) {

    /**
     * 单个命中。
     *
     * @param chunkId 片段标识
     * @param rank    排名，从 1 开始
     */
    public record Hit(ChunkId chunkId, int rank) {
        public Hit {
            if (chunkId == null) {
                throw new IllegalArgumentException("chunkId 不能为空");
            }
            if (rank < 1) {
                throw new IllegalArgumentException("rank 从 1 开始，实际为 " + rank);
            }
        }
    }

    public RetrievedEvidence {
        if (hits == null) {
            throw new IllegalArgumentException("hits 不能为 null，无结果请传空列表");
        }
        hits = List.copyOf(hits);
    }

    /** 空结果。 */
    public static RetrievedEvidence empty() {
        return new RetrievedEvidence(List.of());
    }

    /**
     * 由已按相关性降序排列的片段标识构造，自动编号 rank。
     */
    public static RetrievedEvidence of(List<ChunkId> rankedChunkIds) {
        List<Hit> hits = IntStream.range(0, rankedChunkIds.size())
                .mapToObj(i -> new Hit(rankedChunkIds.get(i), i + 1))
                .toList();
        return new RetrievedEvidence(hits);
    }

    /** 命中数量。 */
    public int size() {
        return hits.size();
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    public Set<ChunkId> chunkIds() {
        return new LinkedHashSet<>(hits.stream().map(Hit::chunkId).toList());
    }

    /**
     * 只保留前 {@code k} 个命中。
     *
     * <p>指标实现依赖此方法表达 "Recall@k" 语义，避免每个指标各自处理截断逻辑。
     */
    public RetrievedEvidence topK(int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k 不能为负数：" + k);
        }
        return new RetrievedEvidence(hits.stream().limit(k).toList());
    }

    /**
     * 指定片段在结果中的排名；未命中时返回 -1。
     *
     * <p>MRR 的核心输入——只看**第一个**相关片段的排名，
     * 因为对用户而言，"正确答案排第 1"和"排第 8"是完全不同的体验。
     */
    public int rankOf(ChunkId chunkId) {
        return hits.stream()
                .filter(h -> h.chunkId().equals(chunkId))
                .mapToInt(Hit::rank)
                .findFirst()
                .orElse(-1);
    }

    /** 是否命中指定片段。 */
    public boolean contains(ChunkId chunkId) {
        return rankOf(chunkId) > 0;
    }
}
