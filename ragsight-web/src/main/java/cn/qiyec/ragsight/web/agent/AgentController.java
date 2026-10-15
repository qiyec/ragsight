package cn.qiyec.ragsight.web.agent;

import cn.qiyec.ragsight.agent.DiagnosisRun;
import cn.qiyec.ragsight.diagnosis.CorpusCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页上的"让 Agent 自己找瓶颈"入口。
 *
 * <p>用 SSE 把每一步实时推给浏览器。这里只做一件事：
 * 把 {@link AgentWebService.AgentEventSink} 的事件翻译成 SSE 事件。
 * 业务判断全在服务里。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /**
     * SSE 连接最多活多久。
     *
     * <p>设得比一次诊断长不少：Agent 要调好几轮大模型，
     * 每轮几秒到几十秒，一次诊断一两分钟很正常。
     * 太短会让连接在诊断完成前被掐断，用户看到"输出到一半没了"。
     *
     * <p>也不设成"永不超时"：万一 Agent 卡死，连接迟早要释放，
     * 不然浏览器那边一直挂着等。
     */
    private static final long SSE_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    private final AgentWebService service;

    public AgentController(AgentWebService service) {
        this.service = service;
    }

    /** 页面上这个入口能不能用，以及有哪些模型可选。 */
    @GetMapping("/options")
    public Map<String, Object> options() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelConfigured", service.modelConfigured());
        result.put("models", AgentWebService.modelChoices());
        result.put("apiKeyEnv", AgentWebService.MODEL_API_KEY_ENV);

        result.put("corpora", service.corpora().stream()
                .map(e -> Map.of("id", e.id(), "name", e.displayName()))
                .toList());

        result.put("hint", service.modelConfigured()
                ? "已检测到大模型凭据，可以让 Agent 自己试。"
                : "没有检测到环境变量 " + AgentWebService.MODEL_API_KEY_ENV
                        + "，这个入口用不了。不过网页上另外两个入口"
                        + "（诊断和对比）不需要任何 Key。");
        return result;
    }

    /**
     * 开一次 Agent 诊断，用 SSE 把过程推出去。
     *
     * <p>这是个 POST 但返回 SSE 流。用 POST 是因为要传参数；
     * 浏览器原生的 {@code EventSource} 只支持 GET，所以前端用
     * {@code fetch} 读流，这在现在的主流浏览器里都支持。
     */
    @PostMapping(value = "/diagnose", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter diagnose(@RequestBody AgentWebService.AgentRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        // 连接断开/超时/出错时把 emitter 收掉。
        // 不做这一步的话，浏览器关掉页面后后台还在闷头跑 Agent，
        // 白花钱。所以这里不是"可选的好习惯"，是必要的。
        emitter.onCompletion(() -> log.debug("SSE 连接正常结束"));
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时，Agent 可能还在跑");
            emitter.complete();
        });
        emitter.onError(e -> log.debug("SSE 连接出错：{}", e.getMessage()));

        service.start(request, new SseSink(emitter));
        return emitter;
    }

    /** 把 sink 事件写成 SSE 事件。 */
    private static final class SseSink implements AgentWebService.AgentEventSink {

        private final SseEmitter emitter;

        SseSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void started(String corpusLabel) {
            send("started", Map.of("corpusLabel", corpusLabel));
        }

        @Override
        public void step(String message) {
            send("step", Map.of("text", message));
        }

        @Override
        public void toolCall(String toolName, String arguments) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tool", toolName);
            data.put("arguments", arguments == null ? "" : arguments);
            send("toolCall", data);
        }

        @Override
        public void toolResult(String toolName, String result) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tool", toolName);
            data.put("result", result == null ? "" : result);
            send("toolResult", data);
        }

        @Override
        public void summary(DiagnosisRun.Summary summary, String conclusion,
                            long elapsedMillis) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("corpusLabel", summary.corpusLabel());
            data.put("experimentCount", summary.experimentCount());
            data.put("configsTried", summary.configsTried());
            data.put("bestConfig", summary.bestConfig());
            data.put("bestRecall", summary.bestRecall());
            data.put("bestMrr", summary.bestMrr());
            data.put("engineFindings", summary.engineFindings());
            data.put("conclusion", conclusion == null ? "" : conclusion);
            data.put("elapsedMillis", elapsedMillis);
            send("summary", data);
        }

        @Override
        public void error(String message) {
            send("error", Map.of("message", message));
        }

        @Override
        public void complete() {
            emitter.complete();
        }

        /**
         * 发一个事件。
         *
         * <p>发送失败通常意味着浏览器那边断开了（关页面、刷新）。
         * 这种情况不该让后台线程抛异常崩掉——它已经在收尾了，
         * 记一笔就够了。
         */
        private void send(String eventName, Object data) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException e) {
                log.debug("推送事件 {} 失败（客户端可能已断开）：{}", eventName, e.getMessage());
            }
        }
    }
}
