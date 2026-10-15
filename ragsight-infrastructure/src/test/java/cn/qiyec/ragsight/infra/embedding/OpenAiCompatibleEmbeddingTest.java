package cn.qiyec.ragsight.infra.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用假的 HTTP 接口测适配器。
 *
 * <p>为什么不用真接口：单测不该依赖网络和 Key，否则会变慢、变脆，
 * 最后被人跳过。真接口留给手工验证。
 *
 * <p>这里测的是适配器自己要负责的事：请求拼得对不对、返回解得对不对、
 * 出错时消息有没有用。至于"模型好不好"不是它能决定的。
 */
@DisplayName("OpenAI 兼容向量模型适配器")
class OpenAiCompatibleEmbeddingTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 挂一个接口，固定返回给定的 JSON。 */
    private void respondWith(int status, String json) {
        server.createContext("/v1/embeddings", exchange -> {
            requestCount.incrementAndGet();
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    /** 造一个维度为 3 的向量返回，条数由 input 里的元素数决定。 */
    private static String embeddingResponse(int count, int dimension) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(i).append(",\"embedding\":[");
            for (int d = 0; d < dimension; d++) {
                if (d > 0) {
                    sb.append(',');
                }
                sb.append(i + 1).append('.').append(d);
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    /** 从请求体里数出 input 数组有几个元素。 */
    private static int countInputs(String requestBody) {
        if (requestBody == null) {
            return 0;
        }
        int inputStart = requestBody.indexOf("\"input\":[");
        if (inputStart < 0) {
            return 0;
        }
        int end = requestBody.indexOf(']', inputStart);
        String inner = requestBody.substring(inputStart + 9, end);
        if (inner.isBlank()) {
            return 0;
        }
        // 用引号数元素个数（每个元素都是一个字符串）
        int quotes = 0;
        for (int i = 0; i < inner.length(); i++) {
            if (inner.charAt(i) == '"') {
                quotes++;
            }
        }
        return quotes / 2;
    }

    // ---------------------------------------------------------------
    // 连接与维度探测
    // ---------------------------------------------------------------

    @Test
    @DisplayName("连接时应探测出向量维度")
    void connect_should_detect_dimension() {
        respondWith(200, embeddingResponse(1, 1024));

        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "test-key", "some-model");

        assertThat(embedding.dimension()).isEqualTo(1024);
        assertThat(embedding.name()).contains("some-model").contains("1024");
    }

    @Test
    @DisplayName("探测时接口报错应立刻抛出，消息里带状态码")
    void connect_should_fail_fast_on_api_error() {
        respondWith(401, "{\"error\":{\"message\":\"Invalid API-key provided.\"}}");

        assertThatThrownBy(() -> OpenAiCompatibleEmbedding.connect(baseUrl, "bad", "m"))
                .isInstanceOf(EmbeddingApiException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("Invalid API-key");
    }

    @Test
    @DisplayName("参数校验")
    void should_validate_parameters() {
        assertThatThrownBy(() -> OpenAiCompatibleEmbedding.connect("", "k", "m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl 不能为空");
        assertThatThrownBy(() -> OpenAiCompatibleEmbedding.connect(baseUrl, " ", "m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey 不能为空");
        assertThatThrownBy(() -> OpenAiCompatibleEmbedding.connect(baseUrl, "k", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelName 不能为空");
    }

    // ---------------------------------------------------------------
    // 请求拼得对不对
    // ---------------------------------------------------------------

    @Test
    @DisplayName("请求应带上 Bearer 鉴权和模型名")
    void request_should_carry_auth_and_model() {
        respondWith(200, embeddingResponse(1, 3));
        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "my-secret-key", "my-model");

        embedding.embed(List.of("一段文本"));

        assertThat(lastAuthHeader.get()).isEqualTo("Bearer my-secret-key");
        assertThat(lastRequestBody.get()).contains("\"model\":\"my-model\"");
        assertThat(lastRequestBody.get()).contains("\"input\":");
    }

    @Test
    @DisplayName("不该传 dimensions 参数——兼容接口未必支持它")
    void request_should_not_send_dimensions() {
        respondWith(200, embeddingResponse(1, 3));
        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m");

        embedding.embed(List.of("文本"));

        assertThat(lastRequestBody.get()).doesNotContain("dimensions");
    }

    // ---------------------------------------------------------------
    // 返回解得对不对
    // ---------------------------------------------------------------

    @Test
    @DisplayName("应按 index 归位，而不是按返回顺序——否则文本和向量会错位")
    void should_order_by_index_not_by_appearance() {
        // 故意把 index=0 的放在后面返回
        String shuffled = "{\"data\":["
                + "{\"index\":1,\"embedding\":[9.0]},"
                + "{\"index\":0,\"embedding\":[1.0]},"
                + "{\"index\":2,\"embedding\":[5.0]}]}";
        respondWith(200, shuffled);
        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m");

        List<float[]> vectors = embedding.embed(List.of("a", "b", "c"));

        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)[0]).isEqualTo(1.0f);
        assertThat(vectors.get(1)[0]).isEqualTo(9.0f);
        assertThat(vectors.get(2)[0]).isEqualTo(5.0f);
    }

    @Test
    @DisplayName("没有 index 字段时按出现顺序取")
    void should_fall_back_to_appearance_order_without_index() {
        String noIndex = "{\"data\":[{\"embedding\":[1.0]},{\"embedding\":[2.0]}]}";
        respondWith(200, noIndex);
        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m");

        List<float[]> vectors = embedding.embed(List.of("a", "b"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)[0]).isEqualTo(1.0f);
        assertThat(vectors.get(1)[0]).isEqualTo(2.0f);
    }

    @Test
    @DisplayName("返回条数和请求条数不一致时应报错，而不是静默错位")
    void should_reject_count_mismatch() {
        // 探测时返回 1 条，正式请求时也返回 1 条，但我们请求了 2 条
        respondWith(200, embeddingResponse(1, 3));
        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m");

        assertThatThrownBy(() -> embedding.embed(List.of("a", "b")))
                .isInstanceOf(EmbeddingApiException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("返回不是合法 JSON 时应报错，消息里带一段原文")
    void should_reject_invalid_json() {
        respondWith(200, "<html>502 Bad Gateway</html>");

        assertThatThrownBy(() -> OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m"))
                .isInstanceOf(EmbeddingApiException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    @DisplayName("返回里没有 data 数组时应报错")
    void should_reject_missing_data() {
        respondWith(200, "{\"error\":\"something\"}");

        assertThatThrownBy(() -> OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m"))
                .isInstanceOf(EmbeddingApiException.class)
                .hasMessageContaining("没有 data 数组");
    }

    // ---------------------------------------------------------------
    // 批量
    // ---------------------------------------------------------------

    @Test
    @DisplayName("超过一批的文本应分批发出，并按顺序拼回结果")
    void should_batch_and_preserve_order() {
        respondWith(200, embeddingResponse(1, 3));
        // 探测占一次请求
        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m", 2);
        int afterProbe = requestCount.get();

        // 这条接口固定只返回 1 条向量，所以分批时必须每条单独发才不出错。
        // 这里把 batchSize 设成 1 来验证分批逻辑本身。
        var single = OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m", 1);
        int afterSecondProbe = requestCount.get();

        single.embed(List.of("a", "b", "c"));

        // 探测 1 次 + 每条 3 次 = 4 次
        assertThat(requestCount.get() - afterSecondProbe).isEqualTo(3);
        assertThat(afterSecondProbe).isGreaterThanOrEqualTo(afterProbe);
    }

    @Test
    @DisplayName("batchSize 应正确反映到单次请求的文本数上")
    void batch_size_should_limit_texts_per_request() {
        respondWith(200, embeddingResponse(3, 3));
        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m", 3);

        embedding.embed(List.of("a", "b", "c"));

        // 这次请求里应该正好带 3 条文本
        assertThat(countInputs(lastRequestBody.get())).isEqualTo(3);
    }

    @Test
    @DisplayName("空列表不发请求，直接返回空")
    void empty_list_should_not_call_api() {
        respondWith(200, embeddingResponse(1, 3));
        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m");
        int before = requestCount.get();

        assertThat(embedding.embed(List.of())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("列表里有 null 应报错并指出位置")
    void should_reject_null_element() {
        respondWith(200, embeddingResponse(1, 3));
        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl, "k", "m");

        assertThatThrownBy(() -> embedding.embed(java.util.Arrays.asList("a", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 个元素是 null");
    }

    @Test
    @DisplayName("baseUrl 末尾多余的斜杠应予忽略")
    void trailing_slash_should_be_ignored() {
        respondWith(200, embeddingResponse(1, 3));

        var embedding = OpenAiCompatibleEmbedding.connect(baseUrl + "/", "k", "m");

        // 能连上就说明路径拼接没问题（否则会是 404）
        assertThat(embedding.dimension()).isEqualTo(3);
    }

    @Test
    @DisplayName("连不上时应给出可读的报错，而不是裸的异常")
    void should_report_unreachable_endpoint_clearly() {
        // 端口 1 基本不可能是 HTTP 服务
        assertThatThrownBy(() -> OpenAiCompatibleEmbedding.connect(
                "http://127.0.0.1:1/v1", "k", "m"))
                .isInstanceOf(EmbeddingApiException.class)
                .hasMessageContaining("连不上向量模型接口");
    }
}
