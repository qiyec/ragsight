package cn.qiyec.ragsight.domain.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按字面重合度打分——最简单的重排方式。
 *
 * <p>算法就是算"问题里的词有多少出现在候选片段里"，再按候选片段长度归一化。
 * 相当于给每个候选算一个加权的词命中比例。
 *
 * <p><b>它为什么值得存在</b>：不是因为它效果好，而是因为它是一个**能离线跑的基线**。
 * 想说"加重排有没有用"，得先有个能跑的重排；想证明"真实重排模型比简单方法好"，
 * 也得先有个简单方法作对照。没有基线的对比是没有说服力的。
 *
 * <p><b>它的能力边界（必须说清）</b>：
 * <ul>
 *   <li>只认字面。问"账单什么时候出"而文档写"账单生成时间"，它一个词都对不上。</li>
 *   <li>真正的重排模型（Cross-Encoder）会把问题和片段一起送进模型，
 *       能理解这种语义关联。这个实现做不到，也不该假装能做到。</li>
 * </ul>
 * 所以它给出的**绝对指标没有参考价值**，只有和别的方案对比时的相对差异有意义。
 *
 * <p>本类不做分词，分词由外部传入——理由和 BM25 一样，见 ADR-004。
 */
public final class LexicalOverlapScorer implements RerankerScorer {

    private final List<String> queryTokens;
    private final Tokenizer tokenizer;

    /**
     * @param queryTokens 已经分好词的问题
     * @param tokenizer   用来给候选片段分词的分词器
     */
    public LexicalOverlapScorer(List<String> queryTokens, Tokenizer tokenizer) {
        if (queryTokens == null) {
            throw new IllegalArgumentException("queryTokens 不能为 null");
        }
        if (tokenizer == null) {
            throw new IllegalArgumentException("tokenizer 不能为 null");
        }
        this.queryTokens = List.copyOf(queryTokens);
        this.tokenizer = tokenizer;
    }

    @Override
    public String name() {
        return "字面重合(" + tokenizer.name() + ")";
    }

    @Override
    public double[] score(String query, List<ScoredChunk> candidates) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates 不能为 null");
        }
        // 查询词去重：同一个词出现多次不该重复加分，
        // 否则"计费 计费 计费"这种输入会把分数刷上去
        Set<String> uniqueQueryTokens = new HashSet<>(queryTokens);

        double[] scores = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            List<String> chunkTokens = tokenizer.tokenize(candidates.get(i).chunk().text());
            scores[i] = overlapScore(uniqueQueryTokens, chunkTokens);
        }
        return scores;
    }

    /**
     * 算命中比例。
     *
     * <p>分母用"候选片段词数"而不是"查询词数"，原因是：
     * 用查询词数当分母时，一个很长的片段只要包含全部查询词就能拿满分，
     * 哪怕它同时塞了一大堆无关内容——而长片段恰恰是上下文里最浪费空间的东西。
     * 用片段词数当分母，长片段会被自然压下去。
     */
    private static double overlapScore(Set<String> queryTokens, List<String> chunkTokens) {
        if (chunkTokens.isEmpty() || queryTokens.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String token : chunkTokens) {
            if (queryTokens.contains(token)) {
                hit++;
            }
        }
        return (double) hit / chunkTokens.size();
    }
}
