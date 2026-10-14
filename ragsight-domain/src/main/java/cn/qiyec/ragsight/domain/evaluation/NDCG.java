package cn.qiyec.ragsight.domain.evaluation;

import cn.qiyec.ragsight.domain.knowledge.ChunkId;

import java.util.ArrayList;
import java.util.List;

/**
 * 归一化折损累计增益 nDCG@k（Normalized Discounted Cumulative Gain）。
 *
 * <pre>
 *   DCG@k  = Σ_{i=1..k}  rel(i) / log₂(i + 1)
 *   IDCG@k = 理想排序下的 DCG@k
 *   nDCG@k = DCG@k / IDCG@k
 * </pre>
 *
 * <p><b>与 MRR 的分工——为什么已经有 MRR 还需要 nDCG</b>：
 * MRR 只看**第一个**相关结果的位置，它假设"找到一个就够了"。
 * 但 RAG 的实际情况是：上下文窗口能放进多个片段，用户可能期望多个答案点
 * （如"列出所有相关条款"）。这时第二个、第三个相关结果的排序质量同样重要，
 * 而 MRR 对它们完全不敏感。
 *
 * <p>nDCG 通过"对靠后位置施加对数折损"来衡量**整个排序列表**的质量，
 * 因此它比 MRR 更全面。三者的诊断分工：
 * <ul>
 *   <li>{@code Recall@k} —— 有没有捞到（召回环节）</li>
 *   <li>{@code MRR@k}    —— 第一个对的排得靠不靠前（排序头部）</li>
 *   <li>{@code nDCG@k}   —— 整体排序质量（排序全局）</li>
 * </ul>
 *
 * <p><b>相关性采用二值</b>：命中期望片段记为 1，否则记 0。
 * 本项目暂不引入分级 relevance（如"高度相关/部分相关"），
 * 因为人工标注分级会显著提高评测集构建成本，而二值已足以定位主要瓶颈。
 * 这是一个明确的取舍，记录于此以免被误认为实现缺失。
 *
 * <p><b>负样本处理</b>：期望集合为空时返回 {@code NaN}（不参与平均）。
 * 理由与 MRR 一致：负样本检验的是"是否硬凑答案"，对排序质量无定义。
 */
public record NDCG(int k) implements Metric {

    public NDCG {
        if (k <= 0) {
            throw new IllegalArgumentException("k 必须为正数，实际为 " + k);
        }
    }

    @Override
    public String name() {
        return "nDCG@" + k;
    }

    @Override
    public double score(GoldenCase testCase, RetrievedEvidence evidence) {
        if (testCase.isNegative()) {
            return Double.NaN;
        }

        RetrievedEvidence topK = evidence.topK(k);
        double dcg = dcg(topK.hits(), testCase.expectedChunkIds(), k);
        double idcg = idcg(testCase.expectedCount(), k);

        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    /**
     * 实际排序的 DCG。
     *
     * <p>遍历前 k 个命中，命中期望片段则按位置折损累加。
     */
    private static double dcg(List<RetrievedEvidence.Hit> hits,
                              java.util.Set<ChunkId> expected,
                              int k) {
        double sum = 0;
        for (int i = 0; i < hits.size() && i < k; i++) {
            if (expected.contains(hits.get(i).chunkId())) {
                // position 从 1 开始；log2(1+1) = 1，即首位不折损
                sum += 1.0 / log2(i + 2);
            }
        }
        return sum;
    }

    /**
     * 理想排序的 DCG：所有期望片段都排在最前面。
     *
     * <p>上限取 min(期望片段数, k)——超出 k 的期望片段无法进入前 k，
     * 因此理想情况也只能排进 k 个。
     */
    private static double idcg(int expectedCount, int k) {
        int limit = Math.min(expectedCount, k);
        double sum = 0;
        for (int i = 1; i <= limit; i++) {
            sum += 1.0 / log2(i + 1);
        }
        return sum;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    /**
     * 供诊断报告使用：返回前 k 名中命中与未命中的位置，便于解释分数成因。
     */
    public List<Integer> relevantPositions(GoldenCase testCase, RetrievedEvidence evidence) {
        List<Integer> positions = new ArrayList<>();
        RetrievedEvidence topK = evidence.topK(k);
        for (int i = 0; i < topK.hits().size(); i++) {
            if (testCase.expectedChunkIds().contains(topK.hits().get(i).chunkId())) {
                positions.add(i + 1);
            }
        }
        return positions;
    }
}
