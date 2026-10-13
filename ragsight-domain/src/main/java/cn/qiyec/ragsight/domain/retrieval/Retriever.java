package cn.qiyec.ragsight.domain.retrieval;

import java.util.List;

/**
 * 检索器（端口）。
 *
 * <p>实现可以是向量召回、关键词召回，或任意组合。本接口只表达
 * "给一个问题，返回 topK 个候选"这一契约，不关心实现细节。
 *
 * <p><b>为什么用接口而不是抽象类</b>：实现可能来自完全不同的技术栈
 * （内存暴力搜索 / pgvector / 远程 HTTP 服务），没有共享逻辑可提取。
 * 强行用抽象类会把无关的模板方法塞给所有实现。
 */
@FunctionalInterface
public interface Retriever {

    /**
     * 召回与问题最相关的片段。
     *
     * @param query 用户问题
     * @param topK  返回的最大候选数，必须为正
     * @return 按相关性降序排列、rank 从 1 开始连续的候选列表；无结果时返回空列表
     */
    List<ScoredChunk> retrieve(String query, int topK);
}
