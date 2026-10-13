package cn.qiyec.ragsight.domain.retrieval;

import java.util.List;

/**
 * 重排器（端口）。
 *
 * <p>重排是 RAG 里"最容易被盲目加进来"的一环。它确实能显著提升 MRR，
 * 但也有真实的代价和失效场景：
 * <ul>
 *   <li><b>延迟成本</b>：Cross-Encoder 需要对每个 (query, chunk) 对做一次前向计算，
 *       候选 20 个就是 20 次，延迟远高于向量检索。</li>
 *   <li><b>无法救回没召回的内容</b>：如果召回集里根本没有正确答案，重排只会
 *       把错的排得更整齐。这也是诊断必须先看 Recall 再看 MRR 的原因。</li>
 * </ul>
 *
 * <p>诊断工具的价值正在于量化这两点：给用户看"加重排到底值不值"。
 */
@FunctionalInterface
public interface Reranker {

    /**
     * 对候选集重新排序。
     *
     * @param query      用户问题
     * @param candidates 待重排候选，按上一阶段的分数降序
     * @param topN       重排后保留的数量，必须为正且通常小于等于候选数
     * @return 重排后的候选列表，rank 重新从 1 连续编号，stage 应为
     *         {@link RetrievalSource#RERANK}；无候选时返回空列表
     */
    List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN);

    /** 重排器名称，用于诊断报告标识配置。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
