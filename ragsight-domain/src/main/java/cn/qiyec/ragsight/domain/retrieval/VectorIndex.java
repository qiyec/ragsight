package cn.qiyec.ragsight.domain.retrieval;

import cn.qiyec.ragsight.domain.knowledge.Chunk;

import java.util.List;

/**
 * 向量索引（端口）。
 *
 * <p>只表达"写入片段"与"按向量相似度搜索"两个能力。
 * 具体是内存暴力搜索、pgvector 还是远程服务，属于基础设施细节。
 *
 * <p><b>为什么检索接口接收的是向量而不是文本</b>：
 * 若接口接收文本，实现就必须自己持有 EmbeddingModel，
 * 于是"用哪个模型"这个决策被埋进索引实现里，
 * 导致无法在同一份索引上对比不同 embedding 模型的效果——
 * 而这恰恰是诊断工具的核心用例之一。
 * 因此向量化由调用方完成，索引只负责存储与相似度计算。
 */
public interface VectorIndex {

    /**
     * 建立索引，替换已有内容。
     *
     * <p>采用"替换"而非"追加"语义：诊断实验需要反复在同一份语料上
     * 用不同切分策略重建索引，追加语义会导致上一轮实验的数据污染本轮结果。
     *
     * @param chunks    待索引片段
     * @param vectors   与 chunks 一一对应的向量，长度必须等于 {@link #dimension()}
     * @param dimension 向量维度
     */
    void rebuild(List<Chunk> chunks, List<float[]> vectors, int dimension);

    /**
     * 按查询向量搜索最相似的片段。
     *
     * @param queryVector 查询向量，长度必须等于索引维度
     * @param topK        返回数量上限，必须为正
     * @return 按相似度降序、rank 从 1 连续编号的结果；索引为空时返回空列表
     */
    List<ScoredChunk> search(float[] queryVector, int topK);

    /** 已索引片段数量。 */
    int size();

    /** 索引维度；尚未建立索引时返回 0。 */
    int dimension();
}
