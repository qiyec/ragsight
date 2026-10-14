package cn.qiyec.ragsight.infra.retriever;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.retrieval.Bm25Scorer;
import cn.qiyec.ragsight.domain.retrieval.RetrievalSource;
import cn.qiyec.ragsight.domain.retrieval.Retriever;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;
import cn.qiyec.ragsight.domain.retrieval.Tokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * BM25 关键词检索器：把 {@link Tokenizer} 与 {@link Bm25Scorer} 组合成 {@link Retriever}。
 *
 * <p><b>它补齐的是向量召回的哪个短板</b>：
 * 向量检索擅长"语义相近但用词不同"，但对**精确术语、产品编号、错误码、
 * 专有名词**往往失灵——这些词在向量空间里容易被语义相近的常见词淹没。
 * BM25 基于精确词元匹配，在这类查询上表现显著更好。
 * 两者互补正是混合检索（+RRF）成为工业标配的原因。
 *
 * <p>这个类只负责把几步串起来：切词 → 打分 → 排序。算法在 {@link Bm25Scorer}（domain），
 * 切词规则在 {@link Tokenizer}（domain 接口）。这个分工见 ADR-004。
 */
public final class Bm25Retriever implements Retriever {

    private final Tokenizer tokenizer;
    private final List<Chunk> chunks;
    private final Bm25Scorer scorer;

    /**
     * @param tokenizer 分词器
     * @param chunks    语料片段，顺序即结果排序时的下标锚点
     * @param scorer    已基于同一份语料构建的打分器
     */
    private Bm25Retriever(Tokenizer tokenizer, List<Chunk> chunks, Bm25Scorer scorer) {
        this.tokenizer = tokenizer;
        this.chunks = chunks;
        this.scorer = scorer;
    }

    /**
     * 用给定语料构建检索器，使用 BM25 默认参数（k1=1.2, b=0.75）。
     *
     * @param tokenizer 分词器
     * @param chunks    语料片段
     */
    public static Bm25Retriever indexed(Tokenizer tokenizer, List<Chunk> chunks) {
        return indexed(tokenizer, chunks, Bm25Scorer.DEFAULT_K1, Bm25Scorer.DEFAULT_B);
    }

    /**
     * 用给定语料构建检索器，可指定 BM25 参数。
     *
     * <p>暴露参数是因为 k1 与 b 的取值会影响召回效果，
     * 而"哪个取值更合适"正是诊断工具要通过消融实验回答的问题。
     *
     * @param tokenizer 分词器
     * @param chunks    语料片段
     * @param k1        词频饱和参数，必须 &gt;= 0
     * @param b         长度归一化参数，必须在 [0, 1]
     */
    public static Bm25Retriever indexed(Tokenizer tokenizer, List<Chunk> chunks, double k1, double b) {
        if (tokenizer == null) {
            throw new IllegalArgumentException("tokenizer 不能为 null");
        }
        if (chunks == null) {
            throw new IllegalArgumentException("chunks 不能为 null");
        }

        List<Chunk> safeChunks = List.copyOf(chunks);
        // 切词结果与 chunks 下标严格一一对应——这是后续按下标取片段的前提
        List<List<String>> tokenizedDocs = new ArrayList<>(safeChunks.size());
        for (Chunk chunk : safeChunks) {
            tokenizedDocs.add(tokenizer.tokenize(chunk.text()));
        }

        return new Bm25Retriever(tokenizer, safeChunks, new Bm25Scorer(tokenizedDocs, k1, b));
    }

    @Override
    public List<ScoredChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，实际为 " + topK);
        }

        List<String> queryTokens = tokenizer.tokenize(query);

        List<ScoredChunk> result = new ArrayList<>();
        int rank = 1;
        for (int docIndex : scorer.rankByScore(queryTokens)) {
            if (rank > topK) {
                break;
            }
            result.add(new ScoredChunk(chunks.get(docIndex),
                    scorer.score(queryTokens, docIndex), rank, RetrievalSource.KEYWORD));
            rank++;
        }
        return List.copyOf(result);
    }

    /** 检索器名称，用于诊断报告标识配置。 */
    public String name() {
        return "bm25(" + tokenizer.name() + ", k1=" + scorer.k1() + ", b=" + scorer.b() + ")";
    }

    /** 已索引片段数量。 */
    public int indexedSize() {
        return chunks.size();
    }

    public Tokenizer tokenizer() {
        return tokenizer;
    }

    public double k1() {
        return scorer.k1();
    }

    public double b() {
        return scorer.b();
    }
}
