package cn.qiyec.ragsight.cli.command;

import cn.qiyec.ragsight.application.DiagnosisEngine;
import cn.qiyec.ragsight.application.DiagnosisReport;
import cn.qiyec.ragsight.application.DiagnosisRequest;
import cn.qiyec.ragsight.application.ExperimentPlan;
import cn.qiyec.ragsight.application.GoldenCaseSetSpec;
import cn.qiyec.ragsight.application.RetrieverConfig;
import cn.qiyec.ragsight.infra.demo.DemoCorpus;
import cn.qiyec.ragsight.infra.demo.DemoEvaluationSet;
import cn.qiyec.ragsight.cli.report.ReportRenderer;
import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.DocumentReader;
import cn.qiyec.ragsight.domain.knowledge.chunking.RecursiveCharacterChunkingStrategy;
import cn.qiyec.ragsight.infra.embedding.DeterministicHashEmbedding;
import cn.qiyec.ragsight.infra.embedding.OpenAiCompatibleEmbedding;
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
            // 注意这里的 %%：picocli 会把说明文本当格式化串处理，
            // 单独一个 % 会触发 "Conversion = '）'" 警告，
            // 帮助信息就退化成未替换的原始字符串。要写成 %%。
            description = "配合 --chunk-size 用：相邻窗口的重叠字符数（默认窗口的 20%%）")
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

    @Option(names = {"--embedding-model"},
            description = "真实向量模型名，比如 text-embedding-v4。"
                    + "指定它就改用真实模型，不再用离线哈希。"
                    + "Key 从环境变量读（默认 RAGSIGHT_API_KEY）。")
    private String embeddingModel;

    @Option(names = {"--embedding-base-url"},
            description = "向量模型接口地址。默认阿里 DashScope 的兼容接口："
                    + "${DEFAULT-VALUE}",
            defaultValue = OpenAiCompatibleEmbeddingOptions.DASHSCOPE_BASE_URL)
    private String embeddingBaseUrl;

    @Option(names = {"--api-key-env"},
            description = "从哪个环境变量读 Key（默认 ${DEFAULT-VALUE}）。"
                    + "Key 不会打印、不会写进报告，也不会落盘。",
            defaultValue = OpenAiCompatibleEmbeddingOptions.API_KEY_ENV)
    private String apiKeyEnv;

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
            if (embeddingModel == null || embeddingModel.isBlank()) {
                System.err.println("向量化方式：离线哈希，只认字面重合，"
                        + "所以下面的绝对分数不代表真实向量模型的效果");
                System.err.println("想看真实效果，加 --embedding-model（见 --help）");
            }
            System.err.println();
        }

        DiagnosisRequest request = new DiagnosisRequest(
                corpusName, documents, caseSpec, plans, retrievers);

        // 决定用哪个向量模型：指定了模型名就用真的，否则用离线哈希。
        // 注意工厂是在每个切分方案下现造的——不同切分切出的片段不一样，
        // 检索器必须按对应的片段重建，共用会让实验互相污染。
        DiagnosisEngine engine;
        if (embeddingModel != null && !embeddingModel.isBlank()) {
            String apiKey = readApiKey();
            // 探测维度和连通性。这里会真发一次请求，失败就直接停下报错，
            // 免得跑到一半才发现 Key 不对。
            var realEmbedding = OpenAiCompatibleEmbedding.connect(
                    embeddingBaseUrl, apiKey, embeddingModel);
            if (!quiet) {
                System.err.println("向量模型：" + realEmbedding.name()
                        + "（接口 " + embeddingBaseUrl + "）");
                System.err.println("Key 来源：环境变量 " + apiKeyEnv + "（不会打印，也不会写进报告）");
                System.err.println();
            }
            engine = new DiagnosisEngine(corpus -> new CompositeRetrieverFactory(
                    () -> realEmbedding,
                    InMemoryVectorIndex::new,
                    SimpleTokenizer::new,
                    corpus));
        } else {
            engine = new DiagnosisEngine(corpus -> new CompositeRetrieverFactory(
                    () -> new DeterministicHashEmbedding(embeddingDimension),
                    InMemoryVectorIndex::new,
                    SimpleTokenizer::new,
                    corpus));
        }

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

    /**
     * 从环境变量读 Key。
     *
     * <p>读不到就报错并说清怎么设，而不是让程序带着空 Key 去请求、
     * 然后收到一个看不懂的 401。
     */
    private String readApiKey() {
        String key = System.getenv(apiKeyEnv);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(String.format(
                    "没有找到 API Key。请先设置环境变量 %s，例如：%n"
                            + "  Windows PowerShell:  $env:%s = \"你的key\"%n"
                            + "  Linux / macOS:       export %s=你的key%n"
                            + "（想用别的环境变量名，加 --api-key-env 指定）",
                    apiKeyEnv, apiKeyEnv, apiKeyEnv));
        }
        return key;
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
            // 自定义的放最前面当基准：加这个参数的人通常就是想拿它和现状比。
            //
            // 去重交给 ExperimentPlan 做（那边有测试）：
            // --chunk-size 90 和内置基线里的 90 字方案实际切得一样，
            // 不去重的话报告里会出现两行同名方案、多跑一组实验。
            return ExperimentPlan.concatWithoutDuplicates(custom,
                    compareRerank
                            ? ExperimentPlan.rerankBaselines(topK)
                            : ExperimentPlan.chunkingBaselines(topK));
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
