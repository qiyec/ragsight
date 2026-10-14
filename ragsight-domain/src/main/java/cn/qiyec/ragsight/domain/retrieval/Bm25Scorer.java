package cn.qiyec.ragsight.domain.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BM25 打分器——纯算法，不含分词、不含 IO。
 *
 * <p>BM25 是关键词（稀疏）召回的工业标准。它弥补向量召回的短板：
 * 向量擅长"语义相近但用词不同"，而 BM25 擅长**精确术语、编号、专有名词**——
 * 这类内容在向量空间里往往被相近的常见词淹没。
 *
 * <pre>
 *   IDF(q) = ln(1 + (N - df(q) + 0.5) / (df(q) + 0.5))
 *   score(q, d) = Σ  IDF(qᵢ) · (tf · (k1 + 1)) / (tf + k1 · (1 - b + b · |d| / avgdl))
 * </pre>
 *
 * <p><b>为什么 IDF 里有个 {@code 1 +}</b>：这是 Lucene 采用的写法。
 * 经典 Robertson IDF 在词出现在超过半数文档时会变成负数，
 * 导致"包含该词的文档反而扣分"这种反直觉行为。
 * 加 1 后 IDF 恒为正，且仍保持"越稀有越重要"的单调性。
 *
 * <p><b>两个参数的含义</b>（它们是诊断报告要给出的调参建议，故必须显式可配）：
 * <ul>
 *   <li>{@code k1}（默认 1.2）：控制**词频饱和**。词出现 10 次不应比出现 5 次
 *       重要一倍——超过某个频次后信息增益递减。k1 越大，高频词的收益越持续</li>
 *   <li>{@code b}（默认 0.75）：控制**长度归一化**。长文档因包含更多词，
 *       偶然命中查询词的概率更高，需按长度惩罚。b=0 关闭归一化，b=1 完全归一化</li>
 * </ul>
 *
 * <p><b>本类不做分词</b>：构造时接收**已分词**的文档。这个边界是刻意的，
 * 见 ADR-004——把不确定的分词抽成端口，让确定的算法留在内核。
 */
public final class Bm25Scorer {

    /** Lucene 默认值。 */
    public static final double DEFAULT_K1 = 1.2;

    /** Lucene 默认值。 */
    public static final double DEFAULT_B = 0.75;

    private final double k1;
    private final double b;
    private final List<List<String>> tokenizedDocs;
    private final Map<String, Long> documentFrequency;
    private final double averageDocLength;
    private final int documentCount;

    /**
     * @param tokenizedDocs 已分词的文档集合，顺序必须与检索候选顺序一致
     * @param k1            词频饱和参数，必须 &gt;= 0
     * @param b             长度归一化参数，必须在 [0, 1]
     */
    public Bm25Scorer(List<List<String>> tokenizedDocs, double k1, double b) {
        if (tokenizedDocs == null) {
            throw new IllegalArgumentException("tokenizedDocs 不能为 null");
        }
        if (k1 < 0) {
            throw new IllegalArgumentException("k1 必须 >= 0，实际为 " + k1);
        }
        if (b < 0 || b > 1) {
            throw new IllegalArgumentException("b 必须在 [0, 1] 之间，实际为 " + b);
        }
        for (int i = 0; i < tokenizedDocs.size(); i++) {
            if (tokenizedDocs.get(i) == null) {
                throw new IllegalArgumentException("第 " + i + " 篇文档的分词结果为 null");
            }
        }

        this.k1 = k1;
        this.b = b;
        this.tokenizedDocs = List.copyOf(tokenizedDocs);
        this.documentCount = tokenizedDocs.size();
        this.documentFrequency = computeDocumentFrequency(tokenizedDocs);
        this.averageDocLength = computeAverageLength(tokenizedDocs);
    }

    public static Bm25Scorer withDefaults(List<List<String>> tokenizedDocs) {
        return new Bm25Scorer(tokenizedDocs, DEFAULT_K1, DEFAULT_B);
    }

    /** 已索引文档数量。 */
    public int documentCount() {
        return documentCount;
    }

    /** 平均文档长度（词元数）。空语料时为 0。 */
    public double averageDocLength() {
        return averageDocLength;
    }

    public double k1() {
        return k1;
    }

    public double b() {
        return b;
    }

    /**
     * 计算查询与指定文档的 BM25 分数。
     *
     * <p>分数**不保证落在 [0,1]**，它是无上界的相关性度量。
     * 这与向量余弦相似度不可比——正是这一点使得多路融合必须使用
     * 排名而非分数（见 {@link ReciprocalRankFusion}）。
     *
     * @param queryTokens  查询分词结果
     * @param documentIndex 文档下标，对应构造时传入的顺序
     * @return BM25 分数；无任何词命中时为 0
     */
    public double score(List<String> queryTokens, int documentIndex) {
        if (queryTokens == null) {
            throw new IllegalArgumentException("queryTokens 不能为 null");
        }
        if (documentIndex < 0 || documentIndex >= documentCount) {
            throw new IndexOutOfBoundsException(
                    "documentIndex " + documentIndex + " 超出范围 [0, " + documentCount + ")");
        }

        List<String> docTokens = tokenizedDocs.get(documentIndex);
        double docLength = docTokens.size();

        // 预先统计查询词在本文档中的词频，避免对每个查询词重复扫描
        Map<String, Integer> termFrequency = new HashMap<>();
        for (String token : docTokens) {
            termFrequency.merge(token, 1, Integer::sum);
        }

        // 查询词可能重复（如"计费 计费"），去重后计算：BM25 假设查询词相互独立，
        // 重复词若累加会人为放大某个词的权重，这不是标准做法。
        Set<String> uniqueQueryTerms = new HashSet<>(queryTokens);

        double total = 0;
        for (String term : uniqueQueryTerms) {
            int tf = termFrequency.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            total += inverseDocumentFrequency(term) * saturation(tf, docLength);
        }
        return total;
    }

    /**
     * 逆文档频率。使用 Lucene 写法，保证恒为正。
     */
    double inverseDocumentFrequency(String term) {
        if (documentCount == 0) {
            return 0;
        }
        long df = documentFrequency.getOrDefault(term, 0L);
        return Math.log(1.0 + (documentCount - df + 0.5) / (df + 0.5));
    }

    /**
     * 词频饱和项（含长度归一化）。
     */
    private double saturation(int termFrequency, double docLength) {
        double lengthRatio = averageDocLength == 0 ? 0 : docLength / averageDocLength;
        double denominator = termFrequency + k1 * (1 - b + b * lengthRatio);
        if (denominator == 0) {
            return 0;
        }
        return termFrequency * (k1 + 1) / denominator;
    }

    private static Map<String, Long> computeDocumentFrequency(List<List<String>> docs) {
        Map<String, Long> df = new HashMap<>();
        for (List<String> doc : docs) {
            // 用 Set 去重：df 统计的是"包含该词的文档数"，不是出现次数
            for (String token : new HashSet<>(doc)) {
                df.merge(token, 1L, Long::sum);
            }
        }
        return Map.copyOf(df);
    }

    private static double computeAverageLength(List<List<String>> docs) {
        if (docs.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (List<String> doc : docs) {
            total += doc.size();
        }
        return (double) total / docs.size();
    }

    /**
     * 对所有文档打分，按下标返回。
     *
     * <p>返回顺序与构造时的文档顺序一致——排序是调用方的职责，
     * 因为不同召回阶段对"如何打破同分"有不同要求。
     */
    public double[] scoreAll(List<String> queryTokens) {
        double[] scores = new double[documentCount];
        for (int i = 0; i < documentCount; i++) {
            scores[i] = score(queryTokens, i);
        }
        return scores;
    }

    /**
     * 返回按分数降序排列的文档下标；同分时按下标升序，保证结果确定（铁律 R5）。
     */
    public List<Integer> rankByScore(List<String> queryTokens) {
        double[] scores = scoreAll(queryTokens);
        List<Integer> order = new ArrayList<>(documentCount);
        for (int i = 0; i < documentCount; i++) {
            order.add(i);
        }
        order.sort(Comparator
                .comparingDouble((Integer i) -> scores[i]).reversed()
                .thenComparing(Comparator.naturalOrder()));
        return order;
    }
}
