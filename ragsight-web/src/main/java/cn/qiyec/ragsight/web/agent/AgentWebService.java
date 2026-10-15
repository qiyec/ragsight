package cn.qiyec.ragsight.web.agent;

import cn.qiyec.ragsight.agent.DiagnosisRun;
import cn.qiyec.ragsight.agent.RagsightDiagnosisAgent;
import cn.qiyec.ragsight.diagnosis.CorpusCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在网页上跑诊断 Agent，并把每一步推给浏览器。
 *
 * <p>为什么用流式推送而不是"等跑完一次性返回"：调 Agent 最难受的体验是
 * **它闷头想半天没动静**，你分不清是在正常工作还是卡住了。
 * 把每一步实时推出去，等待才有意义。
 *
 * <p>为什么用 SSE 而不是 WebSocket：这是单向的（服务端推、客户端听），
 * SSE 就是为这个场景设计的，协议简单、浏览器原生支持、Spring MVC 直接有
 * {@code SseEmitter}。WebSocket 是双向的，这里用不上，白搭复杂度。
 */
@Service
public class AgentWebService {

    private static final Logger log = LoggerFactory.getLogger(AgentWebService.class);

    /**
     * 大模型凭据的环境变量名。
     *
     * <p>和另外两条路不一样：诊断 Agent 必须有大模型，
     * 而 RAGSIGHT_API_KEY 是给向量模型用的（可选）。
     * 两者是不同厂商的东西，不能共用一个变量。
     */
    public static final String MODEL_API_KEY_ENV = "DASHSCOPE_API_KEY";

    /**
     * 跑 Agent 的线程池。
     *
     * <p>为什么需要：Agent 跑起来是几十秒级的阻塞调用，
     * 不能占着 Tomcat 的请求线程。SSE 要求请求线程立刻返回，
     * 由别的线程慢慢往里推事件。
     *
     * <p>线程数是固定的且不大：本地工具，同时跑好几个 Agent 没有意义
     * （每个都在真花钱调模型），排队比并发更合适。
     */
    private final ExecutorService executor =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "ragsight-agent");
                thread.setDaemon(true);
                return thread;
            });

    private final String examplesDir;
    private final CorpusCatalog catalog;

    public AgentWebService(@Value("${ragsight.examples-dir:examples}") String examplesDir) {
        this.examplesDir = examplesDir;
        this.catalog = new CorpusCatalog(examplesDir);
    }

    /** 可选的语料。给页面的下拉框用。 */
    public java.util.List<CorpusCatalog.CorpusEntry> corpora() {
        return catalog.all();
    }

    /** 环境里有没有配大模型凭据。页面据此决定那个入口能不能点。 */
    public boolean modelConfigured() {
        String key = System.getenv(MODEL_API_KEY_ENV);
        return key != null && !key.isBlank();
    }

    /** 可选的模型。列出来免得用户去查文档。 */
    public static java.util.List<String> modelChoices() {
        return java.util.List.of(
                "dashscope:qwen-plus",
                "dashscope:qwen-max",
                "dashscope:qwen-turbo");
    }

    /**
     * 开一次诊断，把过程推给 {@code sink}。
     *
     * <p>这个方法**立刻返回**，实际执行在后台线程里。
     * 所有事件通过 sink 推出去，包括结束和出错。
     *
     * @param sink 接事件的地方，由控制层提供（通常包着 SseEmitter）
     */
    public void start(AgentRequest request, AgentEventSink sink) {
        if (!modelConfigured()) {
            sink.error("没有找到大模型凭据。请设置环境变量 " + MODEL_API_KEY_ENV
                    + " 后重启服务。\n"
                    + "如果你不想用大模型：网页上另外两个入口（诊断和对比）"
                    + "不需要任何 Key。");
            sink.complete();
            return;
        }

        executor.submit(() -> runAgent(request, sink));
    }

    private void runAgent(AgentRequest request, AgentEventSink sink) {
        long startedAt = System.currentTimeMillis();
        // 只结束一次：Agent 正常结束时已经发过 complete，
        // 不能因为 finally 又发一遍（前端会收到两个结束信号）
        AtomicBoolean finished = new AtomicBoolean(false);

        try {
            DiagnosisRun run = DiagnosisRun.builder()
                    .examplesDir(examplesDir)
                    .corpusId(request.corpusId())
                    .topK(request.topK())
                    .model(request.model())
                    .maxIters(request.maxIters())
                    .progressListener(new SinkProgressListener(sink))
                    .build();

            sink.started(run.corpusLabel());

            String conclusion = run.execute();
            DiagnosisRun.Summary summary = run.summary();

            sink.summary(summary, conclusion,
                    System.currentTimeMillis() - startedAt);
            finished.set(true);
            sink.complete();

        } catch (RagsightDiagnosisAgent.AgentRunException e) {
            log.warn("诊断 Agent 执行失败：{}", e.getMessage());
            sink.error("诊断失败：" + e.getMessage()
                    + "\n\n检查一下：凭据对不对？模型名可用吗？网络通不通？");
            finished.set(true);
            sink.complete();

        } catch (RuntimeException e) {
            log.error("诊断 Agent 出现意外错误", e);
            sink.error("出了点意外：" + e.getClass().getSimpleName()
                    + " —— " + e.getMessage());
            finished.set(true);
            sink.complete();

        } finally {
            if (!finished.get()) {
                sink.complete();
            }
        }
    }

    /** 应用退出时收掉线程池。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * 接事件的地方。
     *
     * <p>抽成接口是为了让服务和 HTTP 解耦：服务只管"发生了什么"，
     * 怎么送到浏览器是控制层的事。这样这块逻辑也能脱离 HTTP 测试。
     */
    public interface AgentEventSink {

        /** 开始了，告诉前端这次诊断哪个语料。 */
        void started(String corpusLabel);

        /** Agent 的一步。 */
        void step(String message);

        /** 调用了一个工具。 */
        void toolCall(String toolName, String arguments);

        /** 工具返回。 */
        void toolResult(String toolName, String result);

        /** 最后的结果。 */
        void summary(DiagnosisRun.Summary summary, String conclusion, long elapsedMillis);

        /** 出错。 */
        void error(String message);

        /** 结束（无论成功失败都要发）。 */
        void complete();
    }

    /** 把 ProgressListener 的事件转成 sink 事件。 */
    private record SinkProgressListener(AgentEventSink sink)
            implements RagsightDiagnosisAgent.ProgressListener {

        @Override
        public void onStep(String message) {
            sink.step(message);
        }

        @Override
        public void onToolCall(String toolName, String arguments) {
            sink.toolCall(toolName, arguments);
        }

        @Override
        public void onToolResult(String toolName, String result) {
            sink.toolResult(toolName, result);
        }

        @Override
        public void onError(String message) {
            // 单个步骤出错不一定意味着整体失败（Agent 可能自己绕过去了），
            // 所以记成一步而不是 error
            sink.step("⚠ " + message);
        }
    }

    /** 一次诊断请求。 */
    public record AgentRequest(String corpusId, int topK, String model, int maxIters) {

        /** 页面不填时用的默认值。 */
        public static AgentRequest defaults() {
            return new AgentRequest(null, 5, "dashscope:qwen-plus",
                    RagsightDiagnosisAgent.DEFAULT_MAX_ITERS);
        }
    }
}
