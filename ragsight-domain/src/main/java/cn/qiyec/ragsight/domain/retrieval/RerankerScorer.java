package cn.qiyec.ragsight.domain.retrieval;

import java.util.List;

/**
 * 重排打分接口：给"问题 + 候选片段"，算出每个候选该排多高。
 *
 * <p><b>为什么要把打分和排序分开</b>：打分是纯计算，排序是流程。
 * 两者拆开后，打分的正确性可以用手算的数字验证，
 * 而排序（比如遇到同分怎么办）可以单独测。混在一起就只能一起测。
 *
 * <p>分数只用来**排序**，不要求和别的重排器可比。
 * 换一个重排器，分数的量纲可能完全不同，这是正常的——
 * 报告里比的是排序之后的排名指标（MRR、nDCG），不是分数本身。
 */
public interface RerankerScorer {

    /**
     * 给每个候选算一个分数，**越大越该排前面**。
     *
     * <p>返回的分数要和传入的候选一一对应、顺序一致。
     *
     * @param query      用户问题
     * @param candidates 候选片段，顺序是上一阶段的排名
     * @return 与 candidates 等长的分数数组
     */
    double[] score(String query, List<ScoredChunk> candidates);

    /** 名称，用于在报告里区分用了哪种重排。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
