package cn.qiyec.ragsight.domain.retrieval;

/**
 * 检索结果的来源阶段。
 *
 * <p>诊断的核心依据之一：同一个问题，如果向量召回没命中而关键词召回命中了，
 * 说明瓶颈在向量侧；如果两者都命中但重排后丢失了，说明瓶颈在重排。
 * 没有这个标记，就只能看到最终结果，无法归因。
 */
public enum RetrievalSource {

    /** 向量（稠密）召回。擅长语义相近但用词不同的情况。 */
    VECTOR("向量召回"),

    /** 关键词（稀疏）召回，如 BM25。擅长精确术语、编号、专有名词。 */
    KEYWORD("关键词召回"),

    /** 多路融合后的结果，如 RRF。 */
    FUSION("融合召回"),

    /** 重排后的结果。 */
    RERANK("重排"),

    /**
     * 诊断用：评测集中标注为「应召回到」但实际未命中的占位来源。
     * 用于让"漏召回"的片段也能出现在结果里参与归因分析。
     */
    MISSED("漏召回");

    private final String label;

    RetrievalSource(String label) {
        this.label = label;
    }

    /** 中文标签，用于诊断报告。 */
    public String label() {
        return label;
    }
}
