package cn.qiyec.ragsight.domain.retrieval;

import java.util.List;

/**
 * 把文本变成向量的接口。
 *
 * <p>这里只约定两件事：怎么把文本转成向量、向量有多少维。
 * 具体调哪家模型（DashScope / OpenAI / 本地 Ollama / 离线哈希）属于实现细节。
 *
 * <p><b>为什么维度也要写进接口</b>：向量库在建索引时就得知道维度，
 * 而"维度对不上"是这类系统最常见的低级错误。把它放进约定里，
 * 就能在建索引的时候直接拦住，而不是等检索返回一堆空结果才发现。
 */
public interface EmbeddingModel {

    /**
     * 向量维度。
     *
     * <p>同一个实例返回的维度必须始终一样。如果维度会变，
     * 向量库里存的数据和索引就对不上了。
     */
    int dimension();

    /**
     * 把一批文本转成向量。
     *
     * <p><b>为什么是批量而不是单条</b>：真实的模型调用有固定的额外开销
     * （网络往返、鉴权、预处理）。一条条调会让建索引慢一个数量级。
     * 定成批量接口，调用方就不容易写出这种低效代码。
     *
     * <p><b>返回的顺序必须和传入的顺序完全一致</b>——调用方靠这个顺序
     * 把文本和向量对应起来，错位了就是静默的灾难。
     *
     * @param texts 待转换的文本，不能为空，元素也不能是 null
     * @return 和输入等长的向量列表，每个向量的长度等于 {@link #dimension()}
     */
    List<float[]> embed(List<String> texts);

    /**
     * 转换单条文本的便捷方法。
     *
     * <p>默认实现走批量接口，免得每个实现都重复写这段包装。
     */
    default float[] embedOne(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text 不能为 null");
        }
        return embed(List.of(text)).get(0);
    }

    /** 名称，用于在报告里区分用了哪个模型。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
