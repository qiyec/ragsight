package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.retrieval.Retriever;

/**
 * 检索器工厂（接口）——按配置构建可执行的检索管道。
 *
 * <p><b>为什么需要这个接口</b>：诊断用例（应用层）需要"能跑各种检索方案"，
 * 但构建检索器需要 embedding 模型、向量索引、分词器等具体实现——
 * 那些都属于基础设施。若应用层直接依赖它们，就违反了R2
 * （application 只能依赖 domain）。
 *
 * <p>因此把"按配置构建检索器"这个动作抽象成接口，由基础设施实现。
 * 这是核心不依赖具体实现中"驱动适配器"的标准形态。
 *
 * <p>工厂应当是无状态的：同一配置多次调用应返回等价能力的检索器。
 */
@FunctionalInterface
public interface RetrieverFactory {

    /**
     * 按配置构建检索器。
     *
     * @param config 检索管道配置
     * @return 可执行检索的实例
     * @throws IllegalArgumentException 配置不受支持时
     */
    Retriever create(RetrieverConfig config);
}
