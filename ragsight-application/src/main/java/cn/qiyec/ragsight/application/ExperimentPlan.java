package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.chunking.ChunkingStrategy;
import cn.qiyec.ragsight.domain.knowledge.chunking.FixedWindowChunkingStrategy;
import cn.qiyec.ragsight.domain.knowledge.chunking.RecursiveCharacterChunkingStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 一套实验条件——定义"用哪种切分、取前几条、要不要重排"。
 *
 * <p>诊断做的事就是"固定语料，换条件，看指标怎么变"。这里装的就是那些条件。
 *
 * <p><b>为什么切分是独立的一个维度</b>：
 * "切分粒度该不该调"是 RAG 里被问得最多、也最容易被瞎调的一件事。
 * 把它做成可对比的条件，报告才能回答"切成这样到底有没有用"——
 * 而答案经常是"没用"，那恰恰是最有价值的结论。
 *
 * @param id          标识，用于在报告里区分行
 * @param displayName 展示名
 * @param strategy    切分策略
 * @param topK        每个召回方式取前几条
 * @param rerank      是否在召回之后加一步重排
 */
public record ExperimentPlan(String id, String displayName, ChunkingStrategy strategy,
                             int topK, boolean rerank) {

    public ExperimentPlan {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("实验条件的 id 不能为空");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("切分策略不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，实际为 " + topK);
        }
        displayName = (displayName == null || displayName.isBlank()) ? id : displayName;
    }

    /** 不带重排的构造，省得每次都要写 false。 */
    public ExperimentPlan(String id, String displayName, ChunkingStrategy strategy, int topK) {
        this(id, displayName, strategy, topK, false);
    }

    /** 对文档执行切分。每个实验条件各切一遍，互不影响。 */
    public List<Chunk> chunk(List<Document> documents) {
        List<Chunk> all = new ArrayList<>();
        for (Document document : documents) {
            all.addAll(strategy.chunk(document));
        }
        return List.copyOf(all);
    }

    /** 切分方式的简短说明，用于报告。 */
    public String chunkingLabel() {
        return strategy.describe();
    }

    /**
     * 默认的实验条件：两种切分粒度，各跑一遍不加重排的。
     *
     * <p>刻意选一大一小两个粒度。如果两者指标接近，说明切分不是瓶颈——
     * 这本身就是一条有用的结论，省得你继续在这上面花时间。
     */
    public static ExperimentPlan recursiveDefault(int topK) {
        return new ExperimentPlan("recursive-90", "递归切分 90 字",
                new RecursiveCharacterChunkingStrategy(90, 18), topK);
    }

    public static ExperimentPlan recursiveCoarse(int topK) {
        return new ExperimentPlan("recursive-200", "递归切分 200 字",
                new RecursiveCharacterChunkingStrategy(200, 40), topK);
    }

    public static ExperimentPlan fixedWindowDefault(int topK) {
        return new ExperimentPlan("fixed-90", "固定窗口 90 字",
                new FixedWindowChunkingStrategy(90, 18), topK);
    }

    /**
     * 对比不同切分方案。
     *
     * <p>只换切分，召回方式固定。因为粒度影响"片段里有多少信息"，
     * 策略影响"边界切在哪"，两者都可能成为瓶颈但症状不同，
     * 放在一起对比才好判断该动哪个。
     */
    public static List<ExperimentPlan> chunkingBaselines(int topK) {
        return List.of(
                recursiveDefault(topK),
                recursiveCoarse(topK),
                fixedWindowDefault(topK));
    }

    /**
     * 对比"加不加重排"。
     *
     * <p>关键在**只改一个变量**：同一个切分、同一种召回，区别只在最后有没有重排。
     * 这样比出来的差异才能归因到重排本身。
     *
     * <p>分两组（90 字一组、200 字一组），是为了看"重排的效果会不会因为
     * 切分粒度不同而变化"——如果两组结论不一致，那说明重排的收益
     * 依赖于切分，这本身就是个值得注意的发现。
     *
     * <p>曾经写错过一次：把三种不同切分的方案放在一起，其中两个带重排。
     * 那样一次动了两个变量，报告只能说"没法归因"——那不是发现，
     * 是实验设计的问题。
     */
    public static List<ExperimentPlan> rerankBaselines(int topK) {
        return List.of(
                new ExperimentPlan("recursive-90", "递归切分 90 字",
                        new RecursiveCharacterChunkingStrategy(90, 18), topK, false),
                new ExperimentPlan("recursive-90-rerank", "递归切分 90 字 + 重排",
                        new RecursiveCharacterChunkingStrategy(90, 18), topK, true),
                new ExperimentPlan("recursive-200", "递归切分 200 字",
                        new RecursiveCharacterChunkingStrategy(200, 40), topK, false),
                new ExperimentPlan("recursive-200-rerank", "递归切分 200 字 + 重排",
                        new RecursiveCharacterChunkingStrategy(200, 40), topK, true));
    }
}
