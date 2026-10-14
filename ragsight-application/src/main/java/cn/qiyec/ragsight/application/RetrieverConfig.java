package cn.qiyec.ragsight.application;

/**
 * 一套检索方案的描述——对应报告里的一行。
 *
 * <p><b>为什么只描述"用哪些召回方式"，不带参数</b>：
 * 切分参数、召回条数这些属于"实验条件"，见 {@link ExperimentPlan}。
 * 检索方案本身只回答"准备用哪几条路召回"。
 *
 * <p>把这两件事分开，是为了避免"切分数 × 召回数"的组合在代码里到处传。
 * 报告的行标识由两者拼起来，见 {@link ExperimentId}。
 *
 * @param id          标识，用于在报告里区分行，要求简短唯一
 * @param displayName 展示名
 * @param kind        召回方式
 */
public record RetrieverConfig(String id, String displayName, Kind kind) {

    /**
     * 召回方式。
     *
     * <p>只保留诊断中真正需要对比的三种，不把任意组合都建模出来——
     * 组合爆炸正是要靠让模型自己决策来避免的问题，静态配置覆盖这几档就够。
     */
    public enum Kind {
        /** 只用向量召回。常见系统的默认做法，适合当基准。 */
        VECTOR_ONLY("仅向量召回"),

        /** 只用关键词召回（BM25）。精确术语、编号、专有名词场景的主力。 */
        KEYWORD_ONLY("仅关键词召回"),

        /** 向量 + 关键词，按排名融合。不想调权重时的省事选择。 */
        HYBRID_RRF("向量+关键词 融合");

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
            throw new IllegalArgumentException("检索方案的 id 不能为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        displayName = (displayName == null || displayName.isBlank()) ? id : displayName;
    }

    public static RetrieverConfig vectorOnly() {
        return new RetrieverConfig("vector", "仅向量召回", Kind.VECTOR_ONLY);
    }

    public static RetrieverConfig keywordOnly() {
        return new RetrieverConfig("keyword", "仅关键词召回", Kind.KEYWORD_ONLY);
    }

    public static RetrieverConfig hybridRrf() {
        return new RetrieverConfig("hybrid", "向量+关键词 融合", Kind.HYBRID_RRF);
    }

    /** 三种基线方案，覆盖诊断最常用的对比。 */
    public static java.util.List<RetrieverConfig> standardBaselines() {
        return java.util.List.of(vectorOnly(), keywordOnly(), hybridRrf());
    }
}
