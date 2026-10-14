package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.chunking.ChunkingStrategy;
import cn.qiyec.ragsight.domain.knowledge.chunking.FixedWindowChunkingStrategy;
import cn.qiyec.ragsight.domain.knowledge.chunking.RecursiveCharacterChunkingStrategy;

/**
 * 一套实验条件——定义"用哪种切分、取前几条"。
 *
 * <p>诊断要做的是"固定语料，换条件，看指标怎么变"。这里装的就是那些条件。
 *
 * <p><b>为什么切分要独立成一个维度</b>：
 * "切分粒度该不该调"是 RAG 里被问得最多、也最容易被瞎调的一件事。
 * 把它做成可对比的条件，报告才能回答"切成这样到底有没有用"——
 * 很多情况下答案是"调了也没用"，而那正是最有价值的结论。
 *
 * @param id          标识，用于在报告里区分行
 * @param displayName 展示名
 * @param strategy    切分策略
 * @param topK        每个召回方式取前几条
 */
public record ExperimentPlan(String id, String displayName, ChunkingStrategy strategy, int topK) {

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

    /** 对文档执行切分。每个实验条件各自切一遍，互不影响。 */
    public java.util.List<cn.qiyec.ragsight.domain.knowledge.Chunk> chunk(
            java.util.List<Document> documents) {
        java.util.List<cn.qiyec.ragsight.domain.knowledge.Chunk> all = new java.util.ArrayList<>();
        for (Document document : documents) {
            all.addAll(strategy.chunk(document));
        }
        return java.util.List.copyOf(all);
    }

    /** 切分方式的简短说明，用于报告表头。 */
    public String chunkingLabel() {
        return strategy.describe();
    }

    /**
     * 默认的实验条件：两种常见切分粒度。
     *
     * <p>刻意选一大一小两个粒度。如果两者指标接近，说明切分不是瓶颈——
     * 这本身就是一条有用的结论（省得你继续在这上面花时间）。
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
     * 默认对比的几组条件：两种粒度 × 两种策略。
     *
     * <p>为什么同时换"粒度"和"策略"：粒度影响片段里有多少信息，
     * 策略影响片段边界切在哪。两者都可能成为瓶颈，但症状不同——
     * 放在一起对比，报告才好判断该动哪一个。
     */
    public static java.util.List<ExperimentPlan> standardBaselines(int topK) {
        return java.util.List.of(
                recursiveDefault(topK),
                recursiveCoarse(topK),
                fixedWindowDefault(topK));
    }
}
