package cn.qiyec.ragsight.infra.reranker;

import cn.qiyec.ragsight.domain.retrieval.LexicalOverlapScorer;
import cn.qiyec.ragsight.domain.retrieval.Reranker;
import cn.qiyec.ragsight.domain.retrieval.RerankerScorer;
import cn.qiyec.ragsight.domain.retrieval.RetrievalSource;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;
import cn.qiyec.ragsight.domain.retrieval.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 字面重合重排：用 {@link LexicalOverlapScorer} 打分，再按分数重排。
 *
 * <p>这个类只负责"排序"这件事：算分、排好、重新编号。
 * 打分本身在 domain 里（{@link LexicalOverlapScorer}），因为它需要被精确验证；
 * 这里处理的是流程，不是算法。
 *
 * <p><b>同分怎么办</b>：按片段标识排序。这看起来随意，但比"保持原顺序"好——
 * 如果两个候选分数一样，保留谁在前会影响指标，而原来的顺序本身可能不稳定。
 * 用一个固定的次级键，结果就可复现了。
 */
public final class LexicalOverlapReranker implements Reranker {

    private final Tokenizer tokenizer;

    public LexicalOverlapReranker(Tokenizer tokenizer) {
        if (tokenizer == null) {
            throw new IllegalArgumentException("tokenizer 不能为 null");
        }
        this.tokenizer = tokenizer;
    }

    @Override
    public String name() {
        return "字面重合重排(" + tokenizer.name() + ")";
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (candidates == null) {
            throw new IllegalArgumentException("candidates 不能为 null");
        }
        if (topN <= 0) {
            throw new IllegalArgumentException("topN 必须为正数，实际为 " + topN);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        RerankerScorer scorer = new LexicalOverlapScorer(tokenizer.tokenize(query), tokenizer);
        double[] scores = scorer.score(query, candidates);

        // 排下标而不是排对象本身。用对象的话得靠 indexOf 反查分数，
        // 而 ScoredChunk 的 equals 含 rank，反查结果不可靠；
        // 而且那是 O(n²)。
        List<Integer> order = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator
                .comparingDouble((Integer i) -> scores[i]).reversed()
                .thenComparing(i -> candidates.get(i).chunkId().toString()));

        List<ScoredChunk> result = new ArrayList<>();
        int rank = 1;
        for (Integer i : order) {
            if (rank > topN) {
                break;
            }
            ScoredChunk candidate = candidates.get(i);
            result.add(new ScoredChunk(candidate.chunk(), scores[i], rank,
                    RetrievalSource.RERANK));
            rank++;
        }
        return List.copyOf(result);
    }
}
