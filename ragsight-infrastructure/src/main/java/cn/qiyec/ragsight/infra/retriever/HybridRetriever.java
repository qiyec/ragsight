package cn.qiyec.ragsight.infra.retriever;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.retrieval.ReciprocalRankFusion;
import cn.qiyec.ragsight.domain.retrieval.Retriever;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合检索：多路召回后经 RRF 融合。
 *
 * <p>这是当前工业界的关键词 + 向量混合方案。它之所以有效，是因为两路
 * 的失败模式互补：
 * <ul>
 *   <li>向量路漏掉精确术语/编号——关键词路能补上</li>
 *   <li>关键词路漏掉同义改写——向量路能补上</li>
 * </ul>
 *
 * <p><b>为什么用 RRF 而不是加权求和</b>：见 {@link ReciprocalRankFusion} 的说明——
 * 两路分数量纲不可比（余弦在 [-1,1]，BM25 无上界），
 * RRF 只用排名，天然免去归一化与调权重。
 *
 * <p>这个类只负责把几步串起来：调用各路召回，交给融合器合并。融合算法在 domain。
 */
public final class HybridRetriever implements Retriever {

    private final List<Retriever> routes;
    private final ReciprocalRankFusion fusion;
    /** 每路召回的候选数。融合前应多召回一些，给融合留出腾挪空间。 */
    private final int candidatesPerRoute;

    /**
     * @param routes             各路检索器
     * @param fusion             融合器
     * @param candidatesPerRoute 每路召回的候选数，必须 &gt;= 最终 topK
     */
    public HybridRetriever(List<Retriever> routes, ReciprocalRankFusion fusion,
                           int candidatesPerRoute) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("至少需要一路检索器");
        }
        if (fusion == null) {
            throw new IllegalArgumentException("fusion 不能为空");
        }
        if (candidatesPerRoute <= 0) {
            throw new IllegalArgumentException(
                    "candidatesPerRoute 必须为正数，实际为 " + candidatesPerRoute);
        }
        this.routes = List.copyOf(routes);
        this.fusion = fusion;
        this.candidatesPerRoute = candidatesPerRoute;
    }

    @Override
    public List<ScoredChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，实际为 " + topK);
        }

        List<List<ScoredChunk>> resultLists = new ArrayList<>(routes.size());
        for (Retriever route : routes) {
            resultLists.add(route.retrieve(query, candidatesPerRoute));
        }
        return fusion.fuse(resultLists, topK);
    }

    /** 检索器名称，用于诊断报告标识配置。 */
    public String name() {
        return "hybrid(" + fusion.name() + ", routes=" + routes.size()
                + ", candidates=" + candidatesPerRoute + ")";
    }

    public int routeCount() {
        return routes.size();
    }

    public int candidatesPerRoute() {
        return candidatesPerRoute;
    }
}
