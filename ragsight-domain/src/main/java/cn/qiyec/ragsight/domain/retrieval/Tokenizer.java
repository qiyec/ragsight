package cn.qiyec.ragsight.domain.retrieval;

import java.util.List;

/**
 * 分词接口：把一句话切成一个个词。
 *
 * <p>BM25 要靠它工作。为什么单独定一个接口而不是直接写在 BM25 里？
 * 因为分词往往要依赖外部库（HanLP、jieba、Lucene 的分词器），
 * 而我们要求核心算法不依赖任何第三方库（见 ADR-004）。
 * 所以把分词抽出来，算法留着，实现放外面。
 *
 * <p><b>分词方式值得单独调</b>：按字切、按词切、要不要去停用词——
 * 这些选择会明显影响召回效果。所以它是一个可以对比的实验变量，
 * 而不是写死在算法里的细节。
 */
public interface Tokenizer {

    /**
     * 把文本切成词。
     *
     * <p>约定：
     * <ul>
     *   <li>同样的输入必须得到同样的结果</li>
     *   <li>词应该做过归一化（比如英文转小写），这样 "Pool" 和 "pool" 能匹配上</li>
     *   <li>标点和空白一般要去掉，它们几乎没有检索价值</li>
     * </ul>
     *
     * @param text 待切分的文本，可以是空串（返回空列表）
     * @return 词列表，按出现顺序；不含 null
     */
    List<String> tokenize(String text);

    /** 名称，用于在报告里区分不同分词方式。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
