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
import cn.qiyec.ragsight.infra.reader.CorpusPreparation;
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
 * {@code diagnose} 命令：对一批文档跑一次检索质量诊断。
 *
 * <p>两种用法：
 * <ul>
 *   <li>不带参数：用内置的演示文档，一条命令就能看到报告长什么样</li>
 *   <li>指定 {@code --corpus} 和 {@code --eval}：诊断你自己的文档</li>
 * </ul>
 *
 * <p>刻意不提供"只跑某一种召回方式"的选项。诊断的价值来自**对比**，
 * 只有一个配置的绝对分数回答不了"该不该改"。
 */
@Command(
        name = "diagnose",
        mixinStandardHelpOptions = true,
        description = "对一批文档跑检索质量诊断，输出各配置的指标对比与结论。"
)
public final class DiagnoseCommand implements Callable<Integer> {

    private static final int DEFAULT_TOP_K = 5;

    /** 默认切分窗口。这个值会影响片段数量和召回难度，所以做成可调的。 */
    private static final int DEFAULT_CHUNK_SIZE = 90;

    private static final int DEFAULT_CHUNK_OVERLAP = 18;

    @Option(names = {"-c", "--corpus"},
            description = "文档目录。不指定则使用内置演示文档。")
    private Path corpusDir;

    @Option(names = {"-e", "--eval"},
            description = "评测集文件。指定了 --corpus 就必须同时指定它。"
                    + "格式见 examples/eval-format.txt。")
    private Path evalFile;

    @Option(names = {"-k", "--top-k"},
            description = "每种召回方式取前几条（默认 ${DEFAULT-VALUE}）",
            defaultValue = "" + DEFAULT_TOP_K)
    private int topK;

    @Option(names = {"--chunk-size"},
            description = "切分窗口大小，单位是字符（默认 ${DEFAULT-VALUE}）。"
                    + "调小会让片段变多变碎，召回难度上升。",
            defaultValue = "" + DEFAULT_CHUNK_SIZE)
    private int chunkSize;

    @Option(names = {"--chunk-overlap"},
            description = "相邻窗口的重叠字符数（默认 ${DEFAULT-VALUE}）。"
                    + "重叠是为了避免答案正好被切在边界上。",
            defaultValue = "" + DEFAULT_CHUNK_OVERLAP)
    private int chunkOverlap;

    @Option(names = {"-o", "--output"},
            description = "把报告写到文件；不指定就打印到屏幕")
    private Path outputFile;

    @Option(names = {"--embedding-dim"},
            description = "离线向量维度（默认 ${DEFAULT-VALUE}）。"
                    + "注意这是离线哈希向量化，只认字面，不代表真实模型效果。",
            defaultValue = "" + DeterministicHashEmbedding.DEFAULT_DIMENSION)
    private int embeddingDimension;

    @Option(names = {"-q", "--quiet"},
            description = "只输出报告，不打印运行提示")
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
            System.err.println("--top-k 必须为正数，实际为 " + topK);
            return 2;
        }
        if (chunkSize <= 0) {
            System.err.println("--chunk-size 必须为正数，实际为 " + chunkSize);
            return 2;
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            System.err.println("--chunk-overlap 必须在 0 到 " + (chunkSize - 1)
                    + " 之间（必须小于 --chunk-size），实际为 " + chunkOverlap);
            return 2;
        }
        if (corpusDir != null && evalFile == null) {
            System.err.println("指定了 --corpus 就必须同时指定 --eval。");
            System.err.println("评测集用来告诉工具「哪些片段是正确答案」，没有它算不出指标。");
            System.err.println("格式参考 examples/eval-format.txt。");
            return 2;
        }
        if (corpusDir == null && evalFile != null) {
            System.err.println("指定了 --eval 就必须同时指定 --corpus。");
            return 2;
        }

        // ---- 准备语料和测试集 ----
        List<Chunk> corpus;
        EvaluationSet evalSet;
        String corpusName;

        if (corpusDir != null) {
            CorpusPreparation preparation = CorpusPreparation.from(
                    corpusDir, evalFile, chunkSize, chunkOverlap);
            corpus = preparation.chunks();
            corpusName = preparation.sourceDescription();
            evalSet = preparation.caseSpec().materialize(corpus);

            if (!quiet) {
                System.err.println("文档目录：" + corpusDir.toAbsolutePath());
                System.err.println("读到的文档数：" + preparation.documents().size());
                System.err.println("切分后片段数：" + corpus.size()
                        + "（窗口 " + chunkSize + "，重叠 " + chunkOverlap + "）");
                System.err.println("测试集：" + evalSet.name() + "，"
                        + evalSet.size() + " 个用例（负样本 " + evalSet.negativeCount() + " 个）");
            }
        } else {
            corpus = CompositeRetrieverFactory.requireUniqueIds(DemoCorpus.chunked());
            evalSet = DemoEvaluationSet.build(corpus);
            corpusName = DemoCorpus.corpusName();

            if (!quiet) {
                System.err.println("使用内置演示文档（想诊断自己的文档，加 --corpus 和 --eval）");
                System.err.println("切分后片段数：" + corpus.size());
            }
        }

        if (!quiet) {
            System.err.println("向量化方式：离线哈希，只认字面重合，"
                    + "因此下面的绝对分数不代表真实 embedding 模型的效果");
            System.err.println();
        }

        // ---- 装配。每个配置拿到独立索引，避免实验之间互相污染 ----
        CompositeRetrieverFactory factory = new CompositeRetrieverFactory(
                () -> new DeterministicHashEmbedding(embeddingDimension),
                InMemoryVectorIndex::new,
                SimpleTokenizer::new,
                corpus);

        // 基准取第一个配置——调用方应该把"现在线上用的方案"放在第一位
        List<RetrieverConfig> configs = RetrieverConfig.standardBaselines(topK);
        DiagnosisRequest request = new DiagnosisRequest(corpusName, corpus, evalSet, configs);

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
