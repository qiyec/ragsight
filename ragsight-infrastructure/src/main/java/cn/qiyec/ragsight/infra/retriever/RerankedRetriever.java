package cn.qiyec.ragsight.infra.retriever;

import cn.qiyec.ragsight.application.ExperimentPlan;
import cn.qiyec.ragsight.domain.retrieval.Reranker;
import cn.qiyec.ragsight.domain.retrieval.RetrievalSource;
import cn.qiyec.ragsight.domain.retrieval.Retriever;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;

import java.util.List;

/**
 * 给任意召回器加一层重排。
 *
 * <p>装饰器写法：它自己不召回，只是把调用转给被包装的召回器，
 * 先多要一些候选，再重排后返回前 K 条。
 *
 * <p><b>为什么要先多要候选</b>：重排只能在"已经召回的集合里"重新排序，
 * 它救不回没被召回到的东西。如果召回时就只要 5 条，重排最多也就在这 5 条里
 * 挪位置——那几乎看不出区别，还会让人误以为"重排没用"。
 * 所以这里先按倍数多要一些，让重排有腾挪空间。
 *
 * <p>这个道理也是诊断报告该说清的：**重排提不了召回率**。
 * 如果召回本身不够，加重排是白花钱。
 */
public final class RerankedRetriever implements Retriever {

    private final Retriever delegate;
    private final Reranker reranker;
    private final int topK;

    public RerankedRetriever(Retriever delegate, Reranker reranker, int topK) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为 null");
        }
        if (reranker == null) {
            throw new IllegalArgumentException("reranker 不能为 null");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，实际为 " + topK);
        }
        this.delegate = delegate;
        this.reranker = reranker;
        this.topK = topK;
    }

    @Override
    public List<ScoredChunk> retrieve(String query, int requestedTopK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (requestedTopK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，实际为 " + requestedTopK);
        }

        // 多要一些候选给重排留空间；但不能超过最终要的条数本身
        int candidatesNeeded = requestedTopK * ExperimentPlan.RERANK_CANDIDATE_MULTIPLIER;
        List<ScoredChunk> candidates = delegate.retrieve(query, candidatesNeeded);

        List<ScoredChunk> reranked = reranker.rerank(query, candidates, requestedTopK);

        // 保底：如果重排器返回了空（比如它不支持这种情况），
        // 退回用召回结果截断，而不是让整个诊断得到空结果
        if (reranked.isEmpty() && !candidates.isEmpty()) {
            return candidates.stream()
                    .limit(requestedTopK)
                    .map(c -> new ScoredChunk(c.chunk(), c.score(), c.rank(),
                            RetrievalSource.RERANK))
                    .toList();
        }
        return reranked;
    }

    /** 名称，用于报告里标识这条管道。 */
    public String name() {
        return delegate.getClass().getSimpleName() + " + " + reranker.name();
    }

    /** 重排前会召回多少候选，供报告说明。 */
    public int candidateCount() {
        return topK * ExperimentPlan.RERANK_CANDIDATE_MULTIPLIER;
    }
}
