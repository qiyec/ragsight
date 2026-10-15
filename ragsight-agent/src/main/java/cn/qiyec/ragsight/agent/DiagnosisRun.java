package cn.qiyec.ragsight.agent;

import cn.qiyec.ragsight.diagnosis.CorpusCatalog;
import cn.qiyec.ragsight.diagnosis.DiagnosisService;

import java.util.ArrayList;
import java.util.List;

/**
 * 跑一次"会自己决定试什么"的诊断。
 *
 * <p>把建会话、建 Agent、执行、收尾这几步包成一个调用。
 * {@link DiagnosisSession} 是包内可见的（它是 Agent 的内部状态，
 * 不该让外面随手改），所以外面通过这个类来用。
 *
 * <p>典型用法：
 * <pre>
 *   DiagnosisRun run = DiagnosisRun.builder()
 *           .examplesDir("examples")
 *           .corpusId("corpus")
 *           .model("dashscope:qwen-plus")
 *           .progressListener(listener)
 *           .build();
 *
 *   String conclusion = run.execute();
 * </pre>
 *
 * <p>大模型凭据从环境变量读，不经过这里——避免 Key 出现在配置对象里
 * 被日志打出来。DashScope 读 {@code DASHSCOPE_API_KEY}。
 */
public final class DiagnosisRun {

    private final DiagnosisSession session;
    private final RagsightDiagnosisAgent.Builder agentBuilder;
    private final List<RagsightDiagnosisAgent.ProgressListener> listeners;

    private DiagnosisRun(DiagnosisSession session,
                         RagsightDiagnosisAgent.Builder agentBuilder,
                         List<RagsightDiagnosisAgent.ProgressListener> listeners) {
        this.session = session;
        this.agentBuilder = agentBuilder;
        this.listeners = listeners;
    }

    /**
     * 开始跑。
     *
     * <p>交给 Agent 的任务说明故意保持简短：具体怎么干写在系统提示词里，
     * 这里只需要说清"诊断哪个语料"。
     *
     * @return Agent 的最后总结（它自己写的话，不是诊断引擎的结论）
     */
    public String execute() {
        String task = String.format(
                "请诊断语料「%s」的检索效果。工作方式按你的系统提示来："
                        + "先跑基准，然后一次只改一个东西看效果，最后给结论。",
                session.corpusLabel());

        RagsightDiagnosisAgent.Builder builder = agentBuilder;
        for (RagsightDiagnosisAgent.ProgressListener listener : listeners) {
            builder = builder.progressListener(listener);
        }

        try (RagsightDiagnosisAgent agent = builder.build(session)) {
            return agent.diagnose(task);
        }
    }

    /**
     * 跑完之后看这次实际做了什么。
     *
     * <p>为什么单独暴露：Agent 的最后总结是它自己写的，可能漏东西。
     * 这几个数字是硬事实，可以直接引用。
     */
    public Summary summary() {
        return new Summary(
                session.corpusLabel(),
                session.experimentCount(),
                session.best() == null ? null : session.best().displayName(),
                session.best() == null ? Double.NaN : session.best().recall(),
                session.best() == null ? Double.NaN : session.best().mrr(),
                session.attempted().size(),
                session.allFindings());
    }

    /** 这次诊断的硬数据。 */
    public record Summary(String corpusLabel,
                          int experimentCount,
                          String bestConfig,
                          double bestRecall,
                          double bestMrr,
                          int configsTried,
                          List<String> engineFindings) {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 配置一次诊断。 */
    public static final class Builder {

        private String examplesDir = "examples";
        private String corpusId;
        private int topK = 5;
        private int chunkOverlap = 18;
        private String model = RagsightDiagnosisAgent.DEFAULT_MODEL;
        private int maxIters = RagsightDiagnosisAgent.DEFAULT_MAX_ITERS;
        private final List<RagsightDiagnosisAgent.ProgressListener> listeners = new ArrayList<>();

        private Builder() {
        }

        /** 示例语料目录。相对路径基于服务启动时的工作目录。 */
        public Builder examplesDir(String examplesDir) {
            this.examplesDir = examplesDir;
            return this;
        }

        /** 诊断哪个语料。不填就用默认那个。 */
        public Builder corpusId(String corpusId) {
            this.corpusId = corpusId;
            return this;
        }

        /** 召回条数。 */
        public Builder topK(int topK) {
            if (topK <= 0) {
                throw new IllegalArgumentException("topK 必须为正数，实际为 " + topK);
            }
            this.topK = topK;
            return this;
        }

        /** 切分时相邻片段的重叠字符数。 */
        public Builder chunkOverlap(int chunkOverlap) {
            if (chunkOverlap < 0) {
                throw new IllegalArgumentException("chunkOverlap 不能为负数");
            }
            this.chunkOverlap = chunkOverlap;
            return this;
        }

        /** 用哪个大模型，格式 {@code "厂商:模型名"}。 */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** 最多做多少轮推理-工具循环。 */
        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        /** 加一个进度监听，用来把过程显示出来。 */
        public Builder progressListener(RagsightDiagnosisAgent.ProgressListener listener) {
            if (listener != null) {
                listeners.add(listener);
            }
            return this;
        }

        public DiagnosisRun build() {
            CorpusCatalog catalog = new CorpusCatalog(examplesDir);

            String id = corpusId == null || corpusId.isBlank()
                    ? catalog.defaultId()
                    : corpusId;

            CorpusCatalog.CorpusEntry entry = catalog.byId(id);
            if (entry == null) {
                throw new IllegalArgumentException(String.format(
                        "没有这个语料：%s。可用的有：%s", id,
                        catalog.all().stream()
                                .map(CorpusCatalog.CorpusEntry::id).toList()));
            }

            DiagnosisService service = new DiagnosisService(catalog);

            // 向量模型配置。诊断 Agent 暂时固定用离线向量化——
            // 它要对比的是切分和召回方式，用哪种向量模型不影响这个判断，
            // 反而会让"同一批实验能直接比"这个前提多一个变量。
            DiagnosisService.ApiRequest embeddingSource = new DiagnosisService.ApiRequest(
                    id, null, null, topK, false, false, null, null);

            DiagnosisSession session = new DiagnosisSession(
                    id, entry.displayName(), topK, chunkOverlap, service, embeddingSource);

            RagsightDiagnosisAgent.Builder agentBuilder = RagsightDiagnosisAgent.builder()
                    .model(model)
                    .maxIters(maxIters);

            return new DiagnosisRun(session, agentBuilder, listeners);
        }
    }
}
