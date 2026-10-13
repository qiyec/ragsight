package cn.qiyec.ragsight.domain.evaluation;

import cn.qiyec.ragsight.domain.knowledge.ChunkId;

/**
 * 平均倒数排名 MRR（Mean Reciprocal Rank）。
 *
 * <pre>
 *   单个用例： RR = 1 / rank(第一个被命中的期望片段)    未命中则 RR = 0
 *   整体：     MRR = 所有用例 RR 的算术平均
 * </pre>
 *
 * <p><b>与 Recall 的分工</b>：
 * Recall 回答"找到了吗"，MRR 回答"排得好不好"。
 * 两者必须分开看，因为它们的瓶颈和修复手段完全不同：
 * <ul>
 *   <li>Recall 低 → 召回环节问题（切分把答案切断了 / 缺关键词召回 / embedding 不适配）</li>
 *   <li>Recall 高但 MRR 低 → 排序问题（需要重排，或需要更好的融合策略）</li>
 * </ul>
 * 这正是诊断工具存在的意义：把这两种"效果不好"区分开。
 *
 * <p><b>为什么用倒数排名而不是直接看排名</b>：倒数把排名映射到 (0,1]，
 * 且对不同位置差异敏感——第 1 名 1.0、第 2 名 0.5、第 5 名 0.2，
 * 这样"从第 8 名提到第 1 名"的收益能被清晰体现。
 *
 * <p><b>负样本处理</b>：期望集合为空时返回 {@code NaN}，表示不参与平均。
 * 负样本只检验"是否硬凑答案"，对排序质量没有意义，计入会干扰 MRR 语义。
 */
public record MRR(int k) implements Metric {

    public MRR {
        if (k <= 0) {
            throw new IllegalArgumentException("k 必须为正数，实际为 " + k);
        }
    }

    @Override
    public String name() {
        return "MRR@" + k;
    }

    @Override
    public double score(GoldenCase testCase, RetrievedEvidence evidence) {
        if (testCase.isNegative()) {
            return Double.NaN;
        }

        RetrievedEvidence topK = evidence.topK(k);

        // 取所有期望片段中"最好"的那个排名：如果有多个期望片段被召回，
        // 用户只需要其中一个在靠前位置就能得到正确上下文，故取最小排名。
        int bestRank = testCase.expectedChunkIds().stream()
                .mapToInt(topK::rankOf)
                .filter(rank -> rank > 0)
                .min()
                .orElse(-1);

        return bestRank > 0 ? 1.0 / bestRank : 0.0;
    }
}
