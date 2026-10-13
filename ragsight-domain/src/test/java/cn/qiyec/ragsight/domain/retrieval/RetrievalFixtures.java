package cn.qiyec.ragsight.domain.retrieval;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;

import java.util.List;

/**
 * 测试夹具：构造 ScoredChunk。
 *
 * <p>抽出来的原因：多个测试类都要造候选集，重复写会让测试本身变得难读。
 */
final class RetrievalFixtures {

    private RetrievalFixtures() {
    }

    /** 用 "文档id#序号" 简写构造一个带分值与来源的候选。 */
    static ScoredChunk hit(String doc, int ordinal, double score, int rank, RetrievalSource source) {
        ChunkId id = ChunkId.of(doc, ordinal);
        Chunk chunk = new Chunk(id, id.documentId(), ordinal, "正文-" + id);
        return new ScoredChunk(chunk, score, rank, source);
    }

    /** 由 "文档id#序号" 简写列表构造一路召回结果，rank 自动从 1 递增。 */
    static List<ScoredChunk> route(String doc, RetrievalSource source, int... ordinals) {
        java.util.List<ScoredChunk> list = new java.util.ArrayList<>();
        for (int i = 0; i < ordinals.length; i++) {
            list.add(hit(doc, ordinals[i], 1.0 - i * 0.1, i + 1, source));
        }
        return List.copyOf(list);
    }

    /** 取结果列表的片段标识字符串，便于断言顺序。 */
    static List<String> ids(List<ScoredChunk> chunks) {
        return chunks.stream().map(c -> c.chunkId().toString()).toList();
    }
}
