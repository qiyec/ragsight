package cn.qiyec.ragsight.domain.retrieval;

import cn.qiyec.ragsight.domain.knowledge.Chunk;

import java.util.List;

/**
 * 向量索引接口：存片段、按相似度查。
 *
 * <p>具体是内存里硬算、用 pgvector、还是调远程服务，都属于实现细节。
 *
 * <p><b>为什么查询时传向量而不是传文本</b>：如果传文本，实现就得自己
 * 持有一个 embedding 模型，于是"用哪个模型"这件事被埋进了索引实现里。
 * 结果就是没法在同一份索引上对比不同模型的效果——而这恰恰是诊断工具
 * 最常用的场景之一。所以向量化由调用方做，索引只管存和算相似度。
 */
public interface VectorIndex {

    /**
     * 建索引，覆盖已有内容。
     *
     * <p>是"覆盖"而不是"追加"：诊断要反复在同一份语料上用不同切分方式
     * 重建索引，如果是追加，上一轮实验的数据就会混进这一轮的结果里。
     *
     * @param chunks    要索引的片段
     * @param vectors   与 chunks 一一对应的向量，长度必须等于 {@link #dimension()} 参数
     * @param dimension 向量维度
     */
    void rebuild(List<Chunk> chunks, List<float[]> vectors, int dimension);

    /**
     * 按查询向量找最相似的片段。
     *
     * @param queryVector 查询向量，长度必须和索引维度一致
     * @param topK        最多返回几个，必须为正
     * @return 按相似度从高到低，rank 从 1 连续编号；索引为空就返回空列表
     */
    List<ScoredChunk> search(float[] queryVector, int topK);

    /** 已索引的片段数量。 */
    int size();

    /** 索引维度；还没建索引时返回 0。 */
    int dimension();
}
