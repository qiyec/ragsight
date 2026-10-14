package cn.qiyec.ragsight.cli.command;

import cn.qiyec.ragsight.application.DiagnosisEngine;
import cn.qiyec.ragsight.application.DiagnosisReport;
import cn.qiyec.ragsight.application.DiagnosisRequest;
import cn.qiyec.ragsight.application.RetrieverConfig;
import cn.qiyec.ragsight.cli.demo.DemoCorpus;
import cn.qiyec.ragsight.cli.demo.DemoEvaluationSet;
import cn.qiyec.ragsight.cli.report.ReportRenderer;
import cn.qiyec.ragsight.domain.evaluation.EvaluationSet;
import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.infra.embedding.DeterministicHashEmbedding;
import cn.qiyec.ragsight.infra.index.InMemoryVectorIndex;
import cn.qiyec.ragsight.infra.retriever.CompositeRetrieverFactory;
import cn.qiyec.ragsight.infra.tokenizer.SimpleTokenizer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code rag diagnose} —— 对知识库执行检索质量诊断。
 *
 * <p>当前版本使用**内置演示语料**，目标是让人在 clone 之后一条命令
 * 就能看到诊断报告的形态与价值。接入外部语料（文件 / 目录 / PDF）
 * 属于后续阶段的工作。
 *
 * <p>刻意不提供"只跑某一种配置"的选项：诊断的价值来自**对比**，
 * 单一配置的绝对分数无法回答"该不该改"。
 */
@Command(
        name = "diagnose",
        mixinStandardHelpOptions = true,
        description = "对知识库执行检索质量诊断，输出各阶段的指标对比与结论。"
)
public final class DiagnoseCommand implements Callable<Integer> {

    /** 默认召回数量。 */
    private static final int DEFAULT_TOP_K = 5;

    @Option(names = {"-k", "--top-k"},
            description = "每路召回的候选数量（默认 ${DEFAULT-VALUE}）",
            defaultValue = "" + DEFAULT_TOP_K)
    private int topK;

    @Option(names = {"-o", "--output"},
            description = "将报告写入指定文件；不指定则打印到标准输出")
    private Path outputFile;

    @Option(names = {"--embedding-dim"},
            description = "离线向量维度（默认 ${DEFAULT-VALUE}）。"
                    + "注意：这是离线哈希向量化的维度，不代表真实 embedding 模型。",
            defaultValue = "" + DeterministicHashEmbedding.DEFAULT_DIMENSION)
    private int embeddingDimension;

    @Option(names = {"-q", "--quiet"},
            description = "只输出报告，不输出运行提示与进度信息")
    private boolean quiet;

    @Override
    public Integer call() {
        try {
            return run();
        } catch (RuntimeException | IOException e) {
            System.err.println("诊断失败：" + e.getMessage());
            return 1;
        }
    }

    private Integer run() throws IOException {
        if (topK <= 0) {
            System.err.println("top-k 必须为正数，实际为 " + topK);
            return 2;
        }

        // ---- 准备语料与评测集 ----
        List<Chunk> corpus = CompositeRetrieverFactory.requireUniqueIds(DemoCorpus.chunked());
        EvaluationSet evalSet = DemoEvaluationSet.build(corpus);

        if (!quiet) {
            System.err.println("语料：" + DemoCorpus.corpusName());
            System.err.println("切分后片段数：" + corpus.size());
            System.err.println("评测集：" + DemoEvaluationSet.description());
            System.err.println("向量化方式：离线哈希（无 API Key，不代表真实模型效果）");
            System.err.println();
        }

        // ---- 装配基础设施（每个配置获得独立索引，避免实验互相污染） ----
        CompositeRetrieverFactory factory = new CompositeRetrieverFactory(
                () -> new DeterministicHashEmbedding(embeddingDimension),
                InMemoryVectorIndex::new,
                SimpleTokenizer::new,
                corpus);

        // ---- 执行诊断。基准取第一个配置（仅向量召回，最常见的现状） ----
        List<RetrieverConfig> configs = RetrieverConfig.standardBaselines(topK);
        DiagnosisRequest request = new DiagnosisRequest(
                DemoCorpus.corpusName(), corpus, evalSet, configs);

        DiagnosisReport report = new DiagnosisEngine(factory).diagnose(request);

        // ---- 输出 ----
        String markdown = ReportRenderer.render(report);
        if (outputFile != null) {
            Path parent = outputFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, markdown, StandardCharsets.UTF_8);
            if (!quiet) {
                System.err.println("报告已写入：" + outputFile.toAbsolutePath());
            }
        } else {
            System.out.println(markdown);
        }

        return 0;
    }
}
