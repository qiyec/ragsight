package cn.qiyec.ragsight.domain.retrieval;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;

/**
 * 带相关性分数的片段——检索上下文的通用语言。
 *
 * <p><b>为什么不直接复用 {@link Chunk}</b>：知识库语境关心"这个片段是什么"，
 * 检索语境关心"它有多相关、从哪条路召回的"。两者是不同的问题，
 * 刻意不共用同一个模型（防腐层）。诊断报告需要 {@code stage} 字段来
 * 判断"是召回没召到"还是"重排把好的排下去了"。
 *
 * @param chunk 原始片段
 * @param score 相关性分数，约定**越大越相关**
 * @param rank  在当前结果列表中的排名，从 1 开始（1 表示最相关）
 * @param stage 产出这个结果的检索阶段
 */
public record ScoredChunk(Chunk chunk, double score, int rank, RetrievalSource stage) {

    public ScoredChunk {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk 不能为空");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage 不能为空");
        }
        if (rank < 1) {
            throw new IllegalArgumentException("rank 从 1 开始，实际为 " + rank);
        }
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("score 不能为 NaN");
        }
    }

    /** 片段标识，便于与标注答案比对。 */
    public ChunkId chunkId() {
        return chunk.id();
    }

    /** 生成重新排名的副本，用于重排阶段调整顺序。 */
    public ScoredChunk withRank(int newRank) {
        return new ScoredChunk(chunk, score, newRank, stage);
    }

    /** 生成标记为另一阶段的副本，用于追踪结果在管道中的流转。 */
    public ScoredChunk withStage(RetrievalSource newStage) {
        return new ScoredChunk(chunk, score, rank, newStage);
    }
}
