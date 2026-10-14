package cn.qiyec.ragsight.domain.retrieval;

import java.util.List;

/**
 * 分词器（端口）。
 *
 * <p>把文本切成检索用的词元。抽成端口的理由见
 * [ADR-004](../../../../../../../../docs/adr/004-bm25-in-domain-tokenizer-as-port.md)：
 * BM25 的算法逻辑属于 domain（应在零依赖下可单测），
 * 而分词在实践中常依赖外部库，因此两者必须解耦。
 *
 * <p><b>为什么分词方式值得成为一个可替换的端口</b>：
 * 按单字切分、按词典切分、是否去除停用词——这些选择会显著影响召回率，
 * 因此它是诊断工具应当支持的**实验变量**，而不是焊死在算法里的实现细节。
 */
public interface Tokenizer {

    /**
     * 把文本切分为词元。
     *
     * <p>契约要求：
     * <ul>
     *   <li>确定性：同一输入必须得到相同结果（铁律 R5）</li>
     *   <li>词元应已做规范化（如英文转小写），使"Pool"与"pool"可匹配</li>
     *   <li>标点与空白通常应被丢弃，因为它们几乎不携带检索价值</li>
     * </ul>
     *
     * @param text 待切分文本；允许为空字符串，此时应返回空列表
     * @return 词元列表，按出现顺序；不包含 null 元素
     */
    List<String> tokenize(String text);

    /** 分词器名称，用于诊断报告标识配置。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
