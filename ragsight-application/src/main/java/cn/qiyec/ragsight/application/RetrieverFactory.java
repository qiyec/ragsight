package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.retrieval.Retriever;

/**
 * 造检索器的接口——按方案和召回条数，造出一个能用的检索器。
 *
 * <p><b>为什么需要这个接口</b>：诊断流程要知道"能跑各种召回方案"，
 * 但造检索器要用到向量模型、向量索引、分词器这些具体实现，
 * 那些都在基础设施里。如果诊断流程直接依赖它们，就违反了
 * "用例层只能依赖 domain"这条规矩。
 *
 * <p>所以把"按方案造检索器"这个动作抽成接口，由基础设施去实现。
 *
 * <p>实现应该是无状态的：同一个方案调两次，应该得到能力等价的两个检索器。
 */
@FunctionalInterface
public interface RetrieverFactory {

    /**
     * 按方案造一个检索器。
     *
     * <p>召回条数由调用方传进来，而不是存在方案里——它是实验条件的一部分，
     * 同一套召回方案可能要按不同条数各跑一次（见 {@link ExperimentPlan}）。
     *
     * @param config 召回方案
     * @param topK   每种召回方式取前几条，必须为正
     * @return 可以执行召回的实例
     * @throws IllegalArgumentException 方案不被支持时
     */
    Retriever create(RetrieverConfig config, int topK);
}
