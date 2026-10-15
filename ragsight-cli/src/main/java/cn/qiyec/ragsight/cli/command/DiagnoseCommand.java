package cn.qiyec.ragsight.cli.command;

import cn.qiyec.ragsight.application.DiagnosisEngine;
import cn.qiyec.ragsight.application.DiagnosisReport;
import cn.qiyec.ragsight.application.DiagnosisRequest;
import cn.qiyec.ragsight.application.ExperimentPlan;
import cn.qiyec.ragsight.application.GoldenCaseSetSpec;
import cn.qiyec.ragsight.application.RetrieverConfig;
import cn.qiyec.ragsight.cli.demo.DemoCorpus;
import cn.qiyec.ragsight.cli.demo.DemoEvaluationSet;
import cn.qiyec.ragsight.cli.report.ReportRenderer;
import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.DocumentReader;
import cn.qiyec.ragsight.domain.knowledge.chunking.RecursiveCharacterChunkingStrategy;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code diagnose} 命令：对一批文档跑检索质量诊断。
 *
 * <p>两种用法：
 * <ul>
 *   <li>不带参数：用内置演示文档，一条命令就能看到报告长什么样</li>
 *   <li>指定 {@code --corpus} 和 {@code --eval}：诊断你自己的文档</li>
 * </ul>
 *
 * <p>默认会对比几种切分方式和几种召回方式的组合，所以报告能回答
 * "该调切分还是该换召回"这类问题。只跑一种方案的话，
 * 绝对分数回答不了"该不该改"。
 */
@Command(
        name = "diagnose",
        mixinStandardHelpOptions = true,
        description = "对一批文档跑检索质量诊断，输出各种组合的指标对比与结论。"
)
public final class DiagnoseCommand implements Callable<Integer> {

    private static final int DEFAULT_TOP_K = 5;

    @Option(names = {"-c", "--corpus"},
            description = "文档目录。不指定就用内置演示文档。")
    private Path corpusDir;

    @Option(names = {"-e", "--eval"},
            description = "测试集文件。指定了 --corpus 就必须同时指定它。"
                    + "格式见 examples/eval-format.txt。")
    private Path evalFile;

    @Option(names = {"-k", "--top-k"},
            description = "每种召回方式取前几条（默认 ${DEFAULT-VALUE}）",
            defaultValue = "" + DEFAULT_TOP_K)
    private int topK;

    @Option(names = {"--chunk-size"},
            description = "额外加一个自定义切分：窗口大小，单位字符。"
                    + "不指定就只用内置的几组对比。")
    private Integer chunkSize;

    @Option(names = {"--chunk-overlap"},
            description = "配合 --chunk-size 用：相邻窗口的重叠字符数（默认窗口的 20%）")
    private Integer chunkOverlap;

    @Option(names = {"--rerank"},
            description = "改成对比「加不加重排」，而不是对比切分。"
                    + "报告说「瓶颈在排序」时用这个验证重排到底有没有用。")
    private boolean compareRerank;

    @Option(names = {"--only-chunking"},
            description = "只跑一个切分方案，不和别的切分对比。"
                    + "想快点看到结果时用，实验次数会少很多。")
    private boolean onlyChunking;

    @Option(names = {"-o", "--output"},
            description = "把报告写到文件；不指定就打印到屏幕")
    private Path outputFile;

    @Option(names = {"--embedding-dim"},
            description = "离线向量维度（默认 ${DEFAULT-VALUE}）。"
                    + "注意这是离线哈希向量化，只认字面重合，不代表真实模型效果。",
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
        Integer invalid = validateOptions();
        if (invalid != null) {
            return invalid;
        }

        // ---- 准备文档和测试集 ----
        List<Document> documents;
        GoldenCaseSetSpec caseSpec;
        String corpusName;

        if (corpusDir != null) {
            CorpusPreparation preparation = CorpusPreparation.from(corpusDir, evalFile);
            documents = preparation.documents();
            caseSpec = preparation.caseSpec();
            corpusName = preparation.sourceDescription();
            if (!quiet) {
                System.err.println("文档目录：" + corpusDir.toAbsolutePath());
                System.err.println("读到的文档数：" + documents.size());
            }
        } else {
            DocumentReader reader = DemoCorpus.reader();
            documents = reader.readAll();
            caseSpec = DemoEvaluationSet.build();
            corpusName = DemoCorpus.corpusName();
            if (!quiet) {
                System.err.println("使用内置演示文档（想诊断自己的文档，加 --corpus 和 --eval）");
                System.err.println("读到的文档数：" + documents.size());
            }
        }

        // ---- 要对比哪些方案 ----
        List<ExperimentPlan> plans = buildPlans();
        List<RetrieverConfig> retrievers = RetrieverConfig.standardBaselines();

        if (!quiet) {
            System.err.println("测试集：" + caseSpec.name() + "，"
                    + caseSpec.size() + " 个用例（负样本 " + caseSpec.negativeCount() + " 个）");
            System.err.println("对比方案：" + plans.size() + " 种切分 × "
                    + retrievers.size() + " 种召回 = "
                    + (plans.size() * retrievers.size()) + " 次实验");
            System.err.println("向量化方式：离线哈希，只认字面重合，"
                    + "所以下面的绝对分数不代表真实 embedding 模型的效果");
            System.err.println();
        }

        DiagnosisRequest request = new DiagnosisRequest(
                corpusName, documents, caseSpec, plans, retrievers);

        // 每个切分方案切出来的片段不一样，所以检索器要按对应的那批片段现造，
        // 不能共用一个——共用会让后一个方案覆盖前一个的索引，结果互相污染
        DiagnosisEngine engine = new DiagnosisEngine(corpus -> new CompositeRetrieverFactory(
                () -> new DeterministicHashEmbedding(embeddingDimension),
                InMemoryVectorIndex::new,
                SimpleTokenizer::new,
                corpus));

        DiagnosisReport report = engine.diagnose(request);

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

    /** 决定要对比哪些切分方案。 */
    private List<ExperimentPlan> buildPlans() {
        if (chunkSize != null) {
            int overlap = chunkOverlap != null
                    ? chunkOverlap
                    : Math.max(1, (int) Math.round(chunkSize * 0.2));
            ExperimentPlan custom = new ExperimentPlan(
                    "custom-" + chunkSize,
                    "递归切分 " + chunkSize + " 字",
                    new RecursiveCharacterChunkingStrategy(chunkSize, overlap),
                    topK);
            if (onlyChunking) {
                return List.of(custom);
            }
            // 自定义的放最前面当基准：加这个参数的人通常就是想拿它和现状比
            List<ExperimentPlan> plans = new ArrayList<>();
            plans.add(custom);
            plans.addAll(compareRerank
                    ? ExperimentPlan.rerankBaselines(topK)
                    : ExperimentPlan.chunkingBaselines(topK));
            return List.copyOf(plans);
        }

        if (onlyChunking) {
            return List.of(ExperimentPlan.recursiveDefault(topK));
        }
        return compareRerank
                ? ExperimentPlan.rerankBaselines(topK)
                : ExperimentPlan.chunkingBaselines(topK);
    }

    /** 检查参数。返回 {@code null} 表示都合法。 */
    private Integer validateOptions() {
        if (topK <= 0) {
            System.err.println("--top-k 必须为正数，实际为 " + topK);
            return 2;
        }
        if (chunkSize != null && chunkSize <= 0) {
            System.err.println("--chunk-size 必须为正数，实际为 " + chunkSize);
            return 2;
        }
        if (chunkOverlap != null) {
            if (chunkSize == null) {
                System.err.println("--chunk-overlap 要配合 --chunk-size 一起用。");
                return 2;
            }
            if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
                System.err.println("--chunk-overlap 必须在 0 到 " + (chunkSize - 1)
                        + " 之间（必须小于 --chunk-size），实际为 " + chunkOverlap);
                return 2;
            }
        }
        if (corpusDir != null && evalFile == null) {
            System.err.println("指定了 --corpus 就必须同时指定 --eval。");
            System.err.println("测试集用来告诉工具「哪些片段是正确答案」，没有它算不出指标。");
            System.err.println("格式参考 examples/eval-format.txt。");
            return 2;
        }
        if (corpusDir == null && evalFile != null) {
            System.err.println("指定了 --eval 就必须同时指定 --corpus。");
            return 2;
        }
        return null;
    }
}
