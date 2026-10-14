package cn.qiyec.ragsight.domain.retrieval;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒数排名融合 RRF（Reciprocal Rank Fusion）——多路召回结果的融合算法。
 *
 * <pre>
 *   score(chunk) = Σ_source  weight(source) / (k + rank(source, chunk))
 * </pre>
 *
 * <p><b>为什么需要融合</b>：向量召回擅长语义相近但用词不同的情况，
 * 关键词（BM25）召回擅长精确术语、编号、专有名词。两者互补，
 * 混合检索通常是上线实测中收益最大的一次改动。
 *
 * <p><b>为什么用 RRF 而不是加权求和</b>：
 * <ul>
 *   <li>不同召回器的分数量纲完全不可比——余弦相似度在 [-1,1]，
 *       BM25 是无上界的正数。直接加权求和必须先归一化，
 *       而归一化方式（min-max / z-score）本身又是一个调参黑洞。</li>
 *   <li>RRF <b>只使用排名，不使用分数</b>，天然免去归一化，
 *       也不需要为每个召回器调权重。</li>
 * </ul>
 * 代价是丢失了分数携带的信息量（例如"第 1 名遥遥领先"RRF 看不到）。
 * 这是明确的取舍，记录于此以免被误认为实现缺陷。
 *
 * <p><b>k 的含义</b>：k 越大，排名差异被压得越平；k 越小，头部越占优势。
 * 学界经验值 60，本项目默认 60 但允许配置——它会被纳入消融实验。
 *
 * @param k       平滑常数，必须为正
 * @param weights 各来源权重，键为来源，值为正数；缺省视为 1.0
 */
public record ReciprocalRankFusion(int k, Map<RetrievalSource, Double> weights) {

    /** 学界常用默认值。 */
    public static final int DEFAULT_K = 60;

    public ReciprocalRankFusion {
        if (k <= 0) {
            throw new IllegalArgumentException("k 必须为正数，实际为 " + k);
        }
        weights = weights == null ? Map.of() : Map.copyOf(weights);
        for (Map.Entry<RetrievalSource, Double> e : weights.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                throw new IllegalArgumentException(
                        "权重必须为正数：" + e.getKey() + " = " + e.getValue());
            }
        }
    }

    public static ReciprocalRankFusion defaults() {
        return new ReciprocalRankFusion(DEFAULT_K, Map.of());
    }

    public static ReciprocalRankFusion withK(int k) {
        return new ReciprocalRankFusion(k, Map.of());
    }

    /**
     * 融合多路召回结果。
     *
     * <p>聚合键是「片段标识」，**不是** {@link ScoredChunk} 本身——
     * 后者的 record equals 包含 rank，会把同一片段在不同路的出现当成不同条目，
     * 导致分数无法累加。这是本实现最容易写错的地方，故在此显式说明。
     *
     * @param resultLists 各路召回结果，按排名升序；允许含 null 或空列表
     * @param topK        返回数量上限，必须为正
     * @return 融合结果，按融合分数降序，rank 从 1 连续编号，
     *         来源标记为 {@link RetrievalSource#FUSION}
     */
    public List<ScoredChunk> fuse(List<List<ScoredChunk>> resultLists, int topK) {
        if (resultLists == null) {
            throw new IllegalArgumentException("resultLists 不能为 null");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，实际为 " + topK);
        }

        Map<ChunkId, Double> scoreByChunk = new LinkedHashMap<>();
        Map<ChunkId, Chunk> chunkById = new LinkedHashMap<>();

        for (List<ScoredChunk> results : resultLists) {
            if (results == null) {
                continue;
            }
            for (ScoredChunk hit : results) {
                ChunkId id = hit.chunkId();
                double weight = weights.getOrDefault(hit.stage(), 1.0);
                scoreByChunk.merge(id, weight / (k + hit.rank()), Double::sum);
                chunkById.putIfAbsent(id, hit.chunk());
            }
        }

        if (scoreByChunk.isEmpty()) {
            return List.of();
        }

        List<ChunkId> ordered = new ArrayList<>(scoreByChunk.keySet());
        // 分数降序；同分时按片段标识排序，保证结果确定（R5）
        ordered.sort(Comparator
                .comparingDouble((ChunkId id) -> scoreByChunk.get(id)).reversed()
                .thenComparing(ChunkId::toString));

        List<ScoredChunk> result = new ArrayList<>();
        int rank = 1;
        for (ChunkId id : ordered) {
            if (rank > topK) {
                break;
            }
            result.add(new ScoredChunk(chunkById.get(id), scoreByChunk.get(id), rank,
                    RetrievalSource.FUSION));
            rank++;
        }
        return List.copyOf(result);
    }

    /** 融合器名称，用于诊断报告。 */
    public String name() {
        return "RRF(k=" + k + ")";
    }
}
