package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.retrieval.Retriever;

/**
 * 检索管道配置——描述"要测哪一种检索方案"。
 *
 * <p>这是诊断的**自变量**。一次诊断的本质就是：固定语料与评测集，
 * 改变管道配置，观察指标如何变化。
 *
 * <p><b>为什么不在这里直接持有 {@link Retriever} 实例</b>：
 * 配置需要能被序列化进诊断报告（用户要看到"这一行数据用的是哪种配置"），
 * 而 Retriever 实例不可序列化且依赖运行时装配。
 * 因此配置只描述意图，实例由 {@link RetrieverFactory} 按意图构建。
 *
 * @param id          配置标识，用于报告中的行标识，要求唯一且简短
 * @param displayName 人类可读名称，用于报告展示
 * @param kind        管道类型
 * @param topK        召回数量
 */
public record RetrieverConfig(String id, String displayName, Kind kind, int topK) {

    /**
     * 管道类型。
     *
     * <p>刻意只保留诊断中真正需要对比的三种，而不是把任意组合都建模出来——
     * 组合爆炸正是要靠 Agent 自适应决策来避免的问题（见 ADR-001），
     * 静态配置只需覆盖最关键的三档。
     */
    public enum Kind {
        /** 仅向量召回。基线配置，也是常见系统的默认做法。 */
        VECTOR_ONLY("仅向量召回"),

        /** 仅关键词召回（BM25）。用于验证精确术语场景。 */
        KEYWORD_ONLY("仅关键词召回"),

        /** 向量 + 关键词，经 RRF 融合。工业界混合检索的标准做法。 */
        HYBRID_RRF("向量+关键词 RRF 融合");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public RetrieverConfig {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("配置 id 不能为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，实际为 " + topK);
        }
        displayName = (displayName == null || displayName.isBlank()) ? id : displayName;
    }

    public static RetrieverConfig vectorOnly(int topK) {
        return new RetrieverConfig("vector", "仅向量召回", Kind.VECTOR_ONLY, topK);
    }

    public static RetrieverConfig keywordOnly(int topK) {
        return new RetrieverConfig("keyword", "仅关键词召回", Kind.KEYWORD_ONLY, topK);
    }

    public static RetrieverConfig hybridRrf(int topK) {
        return new RetrieverConfig("hybrid", "向量+关键词 RRF 融合", Kind.HYBRID_RRF, topK);
    }

    /** 三种基线配置，覆盖诊断最常用的对比组合。 */
    public static java.util.List<RetrieverConfig> standardBaselines(int topK) {
        return java.util.List.of(vectorOnly(topK), keywordOnly(topK), hybridRrf(topK));
    }
}
