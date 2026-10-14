package cn.qiyec.ragsight.application;

/**
 * 一次实验的标识——由"切分方案"和"召回方案"拼起来。
 *
 * <p>诊断要跑的是两者的组合：{@code 3 种切分 × 3 种召回 = 9 次实验}。
 * 报告里每一行需要一个稳定的标识，这个 record 就是干这个的。
 *
 * <p><b>为什么不用一个字符串当 key</b>：
 * 报告和测试都要分别按"切分"和"召回"去筛选结果
 * （比如"在最佳切分下，哪种召回最好"）。拆成两个字段才筛得动，
 * 用拼接字符串就得反向解析。
 *
 * @param chunkingId 切分方案的 id
 * @param retrieverId 召回方案的 id
 */
public record ExperimentId(String chunkingId, String retrieverId) {

    public ExperimentId {
        if (chunkingId == null || chunkingId.isBlank()) {
            throw new IllegalArgumentException("切分方案 id 不能为空");
        }
        if (retrieverId == null || retrieverId.isBlank()) {
            throw new IllegalArgumentException("召回方案 id 不能为空");
        }
    }

    public static ExperimentId of(String chunkingId, String retrieverId) {
        return new ExperimentId(chunkingId, retrieverId);
    }

    /**
     * 报告里用的短标识，形如 {@code recursive-90/vector}。
     */
    @Override
    public String toString() {
        return chunkingId + "/" + retrieverId;
    }
}
