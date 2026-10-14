package cn.qiyec.ragsight.domain.retrieval;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;

/**
 * 带分数和排名的片段。
 *
 * <p><b>为什么不直接复用 {@link Chunk}</b>：文档模块关心"这个片段是什么"，
 * 召回模块关心"它排第几、从哪条路召回的"。两件事不一样，
 * 所以各用各的对象，不硬凑一个万能的。
 *
 * <p>诊断报告需要 {@code stage} 字段来判断"是召回没召到"
 * 还是"重排把好的排下去了"——没有它就只能看到最终结果，无法定位原因。
 *
 * @param chunk 原始片段
 * @param score 相关性分数，约定**越大越相关**
 * @param rank  在当前结果里的排名，从 1 开始（1 表示最相关）
 * @param stage 这一步是哪种召回方式产出的
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
