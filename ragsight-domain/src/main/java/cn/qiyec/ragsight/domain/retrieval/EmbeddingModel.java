package cn.qiyec.ragsight.domain.retrieval;

import java.util.List;

/**
 * 文本向量化模型（端口）。
 *
 * <p>在 domain 中只表达"文本 → 向量"与"向量维度"两件事。
 * 具体调用哪家模型（DashScope / OpenAI / 本地 Ollama / 离线哈希）属于基础设施细节。
 *
 * <p><b>为什么维度是接口的一部分</b>：向量库必须在建索引时就知道维度，
 * 且维度不匹配是检索系统最常见的低级错误之一。把它提升为契约的一部分，
 * 可以让我们在装配阶段就校验，而不是等到检索返回空结果才发现。
 */
public interface EmbeddingModel {

    /**
     * 向量维度。
     *
     * <p>同一模型实例的维度必须恒定——实现若返回可变维度，
     * 会导致向量库索引与实际写入数据不一致。
     */
    int dimension();

    /**
     * 把一批文本编码为向量。
     *
     * <p><b>为什么是批量接口而不是单条</b>：真实模型调用存在显著的固定开销
     * （网络往返、鉴权、token 预处理）。逐条调用会让索引构建慢一个数量级。
     * 定义成批量接口可以从契约层面避免调用方写出低效实现。
     *
     * <p><b>返回顺序必须与输入顺序严格一致</b>，这是调用方对齐文本与向量的唯一依据。
     *
     * @param texts 待编码文本，非空；元素不允许为 null
     * @return 与输入等长的向量列表，每个向量的长度必须等于 {@link #dimension()}
     */
    List<float[]> embed(List<String> texts);

    /**
     * 单条文本编码的便捷方法。
     *
     * <p>默认实现复用批量接口，避免各实现重复写这段包装逻辑。
     */
    default float[] embedOne(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text 不能为 null");
        }
        return embed(List.of(text)).get(0);
    }

    /** 模型名称，用于诊断报告标识配置。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
