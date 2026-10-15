package cn.qiyec.ragsight.web.service;

import cn.qiyec.ragsight.application.DiagnosisEngine;
import cn.qiyec.ragsight.application.DiagnosisReport;
import cn.qiyec.ragsight.application.DiagnosisRequest;
import cn.qiyec.ragsight.application.ExperimentPlan;
import cn.qiyec.ragsight.application.RetrieverConfig;
import cn.qiyec.ragsight.domain.knowledge.chunking.FixedWindowChunkingStrategy;
import cn.qiyec.ragsight.domain.knowledge.chunking.RecursiveCharacterChunkingStrategy;
import cn.qiyec.ragsight.infra.embedding.DeterministicHashEmbedding;
import cn.qiyec.ragsight.infra.embedding.OpenAiCompatibleEmbedding;
import cn.qiyec.ragsight.infra.index.InMemoryVectorIndex;
import cn.qiyec.ragsight.infra.reader.CorpusPreparation;
import cn.qiyec.ragsight.infra.retriever.CompositeRetrieverFactory;
import cn.qiyec.ragsight.infra.tokenizer.SimpleTokenizer;
import cn.qiyec.ragsight.web.api.ApiDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 跑一次诊断。
 *
 * <p><b>这个类不做任何判断。</b> 所有结论都是 {@link DiagnosisEngine}
 * 用固定规则推出来的。这里只负责把请求参数翻译成实验方案，
 * 然后把报告原样交给上层去转换。
 *
 * <p>为什么这个边界重要：如果让 Web 层自己推结论，
 * 命令行和网页可能给出不同的结论。诊断工具最不能出的问题
 * 就是"两个入口说法不一致"。
 */
@Service
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);

    /** 环境变量名。和命令行的默认值保持一致。 */
    public static final String API_KEY_ENV = "RAGSIGHT_API_KEY";

    /** 默认接口地址：阿里 DashScope 的 OpenAI 兼容接口。 */
    public static final String DEFAULT_EMBEDDING_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 切分窗口的可选值。跨一个较大的范围，才看得出切分的影响。 */
    private static final List<Integer> CHUNK_SIZE_CHOICES = List.of(60, 90, 120, 200, 300);

    /** 召回条数的可选值。 */
    private static final List<Integer> TOP_K_CHOICES = List.of(3, 5, 8, 10);

    private final CorpusCatalog catalog;

    public DiagnosisService(CorpusCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 跑一次诊断。
     *
     * @param request 页面传来的参数，空字段用默认值
     * @return 诊断报告，以及这轮实际用到的参数
     */
    public RunOutcome diagnose(ApiRequest request) {
        CorpusCatalog.CorpusEntry entry = catalog.byId(request.corpusId());
        if (entry == null) {
            throw new IllegalArgumentException("没有这个语料：" + request.corpusId()
                    + "。可用的有：" + catalog.all().stream()
                    .map(CorpusCatalog.CorpusEntry::id).toList());
        }

        CorpusPreparation preparation = entry.load();
        int topK = request.topK() == null ? 5 : request.topK();
        int chunkSize = request.chunkSize() == null ? 90 : request.chunkSize();
        int chunkOverlap = request.chunkOverlap() == null
                ? Math.max(1, (int) Math.round(chunkSize * 0.2))
                : request.chunkOverlap();

        validate(topK, chunkSize, chunkOverlap);

        List<ExperimentPlan> plans = buildPlans(chunkSize, chunkOverlap, topK,
                Boolean.TRUE.equals(request.includeRerank()));
        List<RetrieverConfig> retrievers = RetrieverConfig.standardBaselines();

        DiagnosisRequest diagnosisRequest = new DiagnosisRequest(
                preparation.sourceDescription(),
                preparation.documents(),
                preparation.caseSpec(),
                plans,
                retrievers);

        DiagnosisEngine engine = buildEngine(request);

        log.info("开始诊断：语料={}，{} 种切分 × {} 种召回 = {} 次实验，topK={}，chunkSize={}",
                entry.displayName(), plans.size(), retrievers.size(),
                plans.size() * retrievers.size(), topK, chunkSize);

        long startedAt = System.currentTimeMillis();
        DiagnosisReport report = engine.diagnose(diagnosisRequest);
        long elapsed = System.currentTimeMillis() - startedAt;

        log.info("诊断完成，{} 个用例，用时 {} ms", report.caseCount(), elapsed);

        return new RunOutcome(report, topK, elapsed);
    }

    /** 一次诊断的产出：报告 + 这轮实际用的参数 + 耗时。 */
    public record RunOutcome(DiagnosisReport report, int topK, long elapsedMillis) {
    }

    private void validate(int topK, int chunkSize, int chunkOverlap) {
        if (topK <= 0) {
            throw new IllegalArgumentException("召回条数必须为正数，实际为 " + topK);
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("切分窗口必须为正数，实际为 " + chunkSize);
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException(String.format(
                    "重叠字符数必须在 0 到 %d 之间（要小于切分窗口 %d），实际为 %d",
                    chunkSize - 1, chunkSize, chunkOverlap));
        }
    }

    /**
     * 造诊断引擎。
     *
     * <p>用真向量模型时，模型实例在所有切分方案之间共用——
     * 它内部有 HTTP 连接池，重复创建反而浪费。
     * 但**索引必须每个方案各造一个**，见下面工厂的注释。
     */
    private DiagnosisEngine buildEngine(ApiRequest request) {
        if (Boolean.TRUE.equals(request.embeddingEnabled())
                && request.embeddingModel() != null
                && !request.embeddingModel().isBlank()) {

            String baseUrl = request.embeddingBaseUrl() == null
                    || request.embeddingBaseUrl().isBlank()
                    ? DEFAULT_EMBEDDING_BASE_URL
                    : request.embeddingBaseUrl();

            OpenAiCompatibleEmbedding embedding = OpenAiCompatibleEmbedding.connect(
                    baseUrl, readApiKey(), request.embeddingModel());

            log.info("用真实向量模型：{}", embedding.name());

            return new DiagnosisEngine(corpus -> new CompositeRetrieverFactory(
                    () -> embedding,             // 模型可以共用
                    InMemoryVectorIndex::new,    // 索引不能共用
                    SimpleTokenizer::new,
                    corpus));
        }

        return new DiagnosisEngine(corpus -> new CompositeRetrieverFactory(
                () -> new DeterministicHashEmbedding(256),
                InMemoryVectorIndex::new,
                SimpleTokenizer::new,
                corpus));
    }

    /**
     * 从环境变量读 Key。
     *
     * <p>读不到就报错并说清怎么设。Web 层不像命令行那样能让用户
     * 现敲一遍环境变量——服务已经启动了，改环境变量得重启。
     * 所以错误消息必须把这件事说透。
     */
    private String readApiKey() {
        String key = System.getenv(API_KEY_ENV);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(String.format(
                    "没有找到 API Key，无法使用真实向量模型。%n"
                            + "请设置环境变量 %s 后**重启服务**：%n"
                            + "  Windows PowerShell:  $env:%s = \"你的key\"%n"
                            + "  Linux / macOS:       export %s=你的key%n"
                            + "或者先关掉「使用真实向量模型」，用离线向量化试流程。",
                    API_KEY_ENV, API_KEY_ENV, API_KEY_ENV));
        }
        return key;
    }

    /**
     * 造要对比的切分方案。
     *
     * <p>用户选的切分参数会生成两种策略（递归、固定窗口），
     * 这样能顺便看出"切分策略"和"切分粒度"哪个影响更大。
     *
     * <p><b>想让报告能判断「重排有没有用」，必须成对给方案</b>：
     * 同一个切分一个带重排、一个不带。只给带重排的，
     * 没有对照，报告会诚实地说"没法归因"。
     */
    private List<ExperimentPlan> buildPlans(int chunkSize, int chunkOverlap,
                                            int topK, boolean includeRerank) {
        int overlap = Math.min(chunkOverlap, chunkSize - 1);
        List<ExperimentPlan> plans = new ArrayList<>();

        String recursiveLabel = "递归切分 " + chunkSize + " 字";
        plans.add(new ExperimentPlan("recursive-" + chunkSize, recursiveLabel,
                new RecursiveCharacterChunkingStrategy(chunkSize, overlap), topK, false));

        if (includeRerank) {
            // 紧挨着放"同一个切分 + 重排"，构成一对干净对照
            plans.add(new ExperimentPlan("recursive-" + chunkSize + "-rerank",
                    recursiveLabel + " + 重排",
                    new RecursiveCharacterChunkingStrategy(chunkSize, overlap), topK, true));
        }

        plans.add(new ExperimentPlan("fixed-" + chunkSize, "固定窗口 " + chunkSize + " 字",
                new FixedWindowChunkingStrategy(chunkSize, overlap), topK, includeRerank));

        return List.copyOf(plans);
    }

    /** 切分窗口的可选值，供页面画下拉框。 */
    public List<Integer> chunkSizeChoices() {
        return CHUNK_SIZE_CHOICES;
    }

    /**
     * 召回方式的可选值。
     *
     * <p>当前页面上这三种是固定全跑的（诊断的价值来自对比，
     * 只跑一种的话绝对分数回答不了"该不该改"）。列出来是为了
     * 页面上能说明"这次对比了哪几种"。
     */
    public List<ApiDtos.Choice> retrieverChoices() {
        return RetrieverConfig.standardBaselines().stream()
                .map(c -> new ApiDtos.Choice(c.id(), c.displayName()))
                .toList();
    }

    /** 召回条数的可选值。 */
    public List<Integer> topKChoices() {
        return TOP_K_CHOICES;
    }

    /** 环境里有没有配 Key，用来决定页面上那个开关默认可不可用。 */
    public boolean realEmbeddingConfigured() {
        String key = System.getenv(API_KEY_ENV);
        return key != null && !key.isBlank();
    }

    /** 请求参数。字段可空，空就用默认值。 */
    public record ApiRequest(
            String corpusId,
            Integer chunkSize,
            Integer chunkOverlap,
            Integer topK,
            Boolean includeRerank,
            Boolean embeddingEnabled,
            String embeddingModel,
            String embeddingBaseUrl) {
    }
}
