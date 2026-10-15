package cn.qiyec.ragsight.web.api;

import cn.qiyec.ragsight.web.service.CorpusCatalog;
import cn.qiyec.ragsight.web.service.DiagnosisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTTP 接口。只有两个：拿可选项、跑诊断。
 *
 * <p>刻意做得很薄——不在这里做业务判断，只负责把请求转给服务、
 * 把结果转成 JSON。判断逻辑全在 {@code DiagnosisEngine} 里，
 * 因为命令行和网页必须给出同样的结论。
 */
@RestController
@RequestMapping("/api")
public class DiagnoseController {

    private static final Logger log = LoggerFactory.getLogger(DiagnoseController.class);

    private final CorpusCatalog catalog;
    private final DiagnosisService service;

    public DiagnoseController(CorpusCatalog catalog, DiagnosisService service) {
        this.catalog = catalog;
        this.service = service;
    }

    /**
     * 页面上所有可调项，以及它们的可选值和默认值。
     *
     * <p>页面打开时先调这个，把所有下拉框填好。
     */
    @GetMapping("/options")
    public ApiDtos.Options options() {
        List<ApiDtos.CorpusInfo> corpora = catalog.all().stream()
                .map(e -> new ApiDtos.CorpusInfo(e.id(), e.displayName(), e.description()))
                .toList();

        boolean realEmbedding = service.realEmbeddingConfigured();
        String hint = realEmbedding
                ? "已检测到环境变量里的 API Key，可以打开开关用真实向量模型。"
                : "没有检测到环境变量 " + DiagnosisService.API_KEY_ENV
                        + "。默认用离线向量化：不联网、不用 Key，"
                        + "但它只认字面重合，所以绝对分数没有参考价值，"
                        + "只能用来试流程。";

        return new ApiDtos.Options(
                corpora,
                catalog.defaultId(),
                service.retrieverChoices(),
                service.chunkSizeChoices(),
                service.topKChoices(),
                realEmbedding,
                hint);
    }

    /**
     * 跑一次诊断。
     *
     * <p>这是个同步接口。默认配置下（离线向量化、十来个用例）
     * 通常在一秒内返回，没必要做成异步任务。
     * 将来如果用真实模型并且语料很大，再考虑改成提交任务 + 轮询。
     */
    @PostMapping("/diagnose")
    public ApiDtos.DiagnoseResult diagnose(@RequestBody ApiDtos.DiagnoseRequest request) {
        DiagnosisService.ApiRequest apiRequest = new DiagnosisService.ApiRequest(
                request.corpusId(),
                request.chunkSize(),
                request.chunkOverlap(),
                request.topK(),
                request.includeRerank(),
                request.embeddingEnabled(),
                request.embeddingModel(),
                request.embeddingBaseUrl());

        DiagnosisService.RunOutcome outcome = service.diagnose(apiRequest);

        return ResultBuilder.build(outcome.report(), outcome.topK(), outcome.elapsedMillis());
    }

    /**
     * 把异常转成给用户看的中文提示。
     *
     * <p>不返回堆栈——页面上看到一大段 Java 异常没有任何帮助。
     * 但完整信息会记进日志，排查时能从日志里拿到。
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleUserError(RuntimeException e) {
        log.warn("诊断请求被拒绝：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage() == null ? "请求有误" : e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedError(Exception e) {
        log.error("诊断过程中出现意外错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "诊断过程中出错了：" + e.getClass().getSimpleName()
                        + " —— " + e.getMessage() + "。完整信息在服务端日志里。"));
    }
}
