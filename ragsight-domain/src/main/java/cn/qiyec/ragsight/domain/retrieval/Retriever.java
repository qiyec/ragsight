package cn.qiyec.ragsight.domain.retrieval;

import java.util.List;

/**
 * 召回器接口：给一个问题，返回最相关的几个片段。
 *
 * <p>实现可以是向量召回、关键词召回，或者两者组合。这里只约定
 * "给我问题，还我候选"这件事，不关心背后用什么技术。
 *
 * <p><b>为什么定成接口而不是抽象类</b>：不同实现的底层完全不一样
 * （内存里算、查向量库、调远程服务），没有公共代码可以复用。
 * 硬用抽象类反而会塞给所有实现一堆用不上的方法。
 */
@FunctionalInterface
public interface Retriever {

    /**
     * 召回与问题最相关的片段。
     *
     * @param query 用户问题
     * @param topK  最多返回几个，必须为正
     * @return 按相关性从高到低排列，rank 从 1 开始连续编号；没结果就返回空列表
     */
    List<ScoredChunk> retrieve(String query, int topK);
}
