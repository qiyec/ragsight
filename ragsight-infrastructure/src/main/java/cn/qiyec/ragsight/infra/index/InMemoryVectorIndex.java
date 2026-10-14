package cn.qiyec.ragsight.infra.index;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.retrieval.RetrievalSource;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;
import cn.qiyec.ragsight.domain.retrieval.VectorIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内存向量索引：暴力（穷举）相似度搜索。
 *
 * <p><b>为什么 MVP 阶段用暴力搜索而不是引入向量数据库</b>：
 * <ul>
 *   <li>数万条片段的穷举计算在现代 CPU 上是毫秒级，完全够用</li>
 *   <li>诊断工具的价值在于"度量准不准"，不在于"检索快不快"</li>
 *   <li>引入向量库会让"clone 即可跑"（R4）失效——需要 Docker、需要建表、需要连接</li>
 * </ul>
 * 当语料规模真的到达暴力搜索不可接受时，再以适配器形式接入 pgvector，
 * 而 domain 的 {@link VectorIndex} 契约无需任何改动。这正是接口-实现的收益。
 *
 * <p><b>相似度选择</b>：使用余弦相似度。虽然本项目的离线 embedding 已做 L2 归一化
 * （此时余弦等价于点积），但这里仍然显式做完整余弦计算，原因是该实现也要能
 * 正确服务于**未归一化**的向量（真实模型未必归一化）。依赖隐含前提的代码是维护陷阱。
 *
 * <p><b>线程安全</b>：本类不是线程安全的。诊断实验按顺序执行，无需并发；
 * 若将来要并发跑实验，每个实验应持有独立索引实例（也符合实验隔离的语义）。
 */
public final class InMemoryVectorIndex implements VectorIndex {

    private final List<Chunk> chunks = new ArrayList<>();
    private final List<float[]> vectors = new ArrayList<>();
    private int dimension;

    @Override
    public void rebuild(List<Chunk> newChunks, List<float[]> newVectors, int newDimension) {
        if (newChunks == null || newVectors == null) {
            throw new IllegalArgumentException("chunks 与 vectors 均不能为 null");
        }
        if (newChunks.size() != newVectors.size()) {
            throw new IllegalArgumentException(
                    "chunks(" + newChunks.size() + ") 与 vectors(" + newVectors.size()
                            + ") 数量不一致——这通常意味着向量化时丢失或错位了片段");
        }
        if (newDimension <= 0) {
            throw new IllegalArgumentException("dimension 必须为正数，实际为 " + newDimension);
        }
        for (int i = 0; i < newVectors.size(); i++) {
            float[] v = newVectors.get(i);
            if (v == null || v.length != newDimension) {
                throw new IllegalArgumentException(
                        "第 " + i + " 个向量维度为 " + (v == null ? "null" : v.length)
                                + "，与声明的维度 " + newDimension + " 不一致");
            }
        }

        chunks.clear();
        vectors.clear();
        chunks.addAll(newChunks);
        vectors.addAll(newVectors);
        this.dimension = newDimension;
    }

    @Override
    public List<ScoredChunk> search(float[] queryVector, int topK) {
        if (queryVector == null) {
            throw new IllegalArgumentException("queryVector 不能为 null");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，实际为 " + topK);
        }
        if (dimension == 0) {
            // 尚未建立索引：返回空结果而不是报错，让"空知识库"成为合法场景
            return List.of();
        }
        if (queryVector.length != dimension) {
            throw new IllegalArgumentException(
                    "查询向量维度为 " + queryVector.length + "，与索引维度 " + dimension
                            + " 不一致——通常是换 embedding 模型后忘记重建索引");
        }

        List<Candidate> candidates = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            candidates.add(new Candidate(chunks.get(i), cosine(queryVector, vectors.get(i))));
        }

        // 相似度降序；同分时按片段标识排序，保证结果可复现（R5）
        candidates.sort(Comparator
                .comparingDouble(Candidate::score).reversed()
                .thenComparing(c -> c.chunk().id().toString()));

        List<ScoredChunk> result = new ArrayList<>();
        int rank = 1;
        for (Candidate c : candidates) {
            if (rank > topK) {
                break;
            }
            result.add(new ScoredChunk(c.chunk(), c.score(), rank, RetrievalSource.VECTOR));
            rank++;
        }
        return List.copyOf(result);
    }

    @Override
    public int size() {
        return chunks.size();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    /**
     * 余弦相似度。
     *
     * <p>任一侧为零向量时返回 0（无相似性可言），而不是产生 NaN——
     * NaN 会污染排序与后续的指标计算。
     */
    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Candidate(Chunk chunk, double score) {
    }
}
