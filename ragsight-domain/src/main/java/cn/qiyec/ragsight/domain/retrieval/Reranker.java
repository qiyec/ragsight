package cn.qiyec.ragsight.domain.retrieval;

import java.util.List;

/**
 * 重排接口：把召回来的候选重新排个序。
 *
 * <p>重排是 RAG 里最容易被盲目加进来的一环。它确实能明显提升排序质量，
 * 但有两个真实的代价和局限，值得先说清楚：
 *
 * <ul>
 *   <li><b>慢</b>：重排模型要对每个「问题 + 片段」的组合单独算一次。
 *       候选有 20 个就要算 20 次，延迟远高于向量召回。</li>
 *   <li><b>救不回没召回到的东西</b>：如果候选里根本没有正确答案，
 *       重排只能把错的排得更整齐。所以诊断必须先看召回率，再看排序质量。</li>
 * </ul>
 *
 * <p>这个工具的价值就在于把这两点量化出来：让用户看到"加重排到底值不值"。
 */
@FunctionalInterface
public interface Reranker {

    /**
     * 对候选重新排序。
     *
     * @param query      用户问题
     * @param candidates 待重排的候选，按上一阶段的分数从高到低
     * @param topN       重排后保留几个，通常少于候选数
     * @return 重排后的结果，rank 重新从 1 连续编号，来源标记为
     *         {@link RetrievalSource#RERANK}；没候选就返回空列表
     */
    List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN);

    /** 重排器名称，用于在报告里区分不同配置。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
