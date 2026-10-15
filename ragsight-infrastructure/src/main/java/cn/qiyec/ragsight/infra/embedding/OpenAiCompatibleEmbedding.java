package cn.qiyec.ragsight.infra.embedding;

import cn.qiyec.ragsight.domain.retrieval.EmbeddingModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 调真实向量模型的适配器，走 OpenAI 兼容的接口格式。
 *
 * <p>这一类接口现在很通用，DashScope（百炼）、硅基流动、OpenAI、
 * 以及本地跑的 vLLM / Ollama 都支持。所以只写一个适配器，
 * 换供应商时改 {@code baseUrl} 和模型名就行，不用改代码。
 *
 * <p><b>为什么维度要探测而不是写死</b>：不同模型的维度差别很大
 * （有的 1024，有的 1536，有的 4096），而且换成别的模型时会变。
 * 与其让用户去查文档填一个数字，不如启动时拿一句话试一下，
 * 直接问出来。探测失败会立刻报错，而不是等到检索时才发现维度对不上。
 *
 * <p><b>为什么批量而不是逐条</b>：真实的接口调用有固定开销
 * （网络往返、鉴权），一条条调会慢一个数量级。所以把要编码的文本
 * 分成若干批发出去。
 *
 * <p>这个类只在基础设施层，core 里看不到它，也没有 HTTP 或 JSON 依赖。
 */
public final class OpenAiCompatibleEmbedding implements EmbeddingModel {

    /** 一次请求最多带多少条文本。太大容易被服务端拒。 */
    public static final int DEFAULT_BATCH_SIZE = 16;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final int batchSize;
    private final int dimension;

    private OpenAiCompatibleEmbedding(HttpClient httpClient,
                                      String baseUrl,
                                      String apiKey,
                                      String modelName,
                                      int batchSize,
                                      int dimension) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.batchSize = batchSize;
        this.dimension = dimension;
    }

    /**
     * 连上接口，并探测出向量维度。
     *
     * @param baseUrl   接口地址，如 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}
     * @param apiKey    API Key
     * @param modelName 模型名，如 {@code text-embedding-v4}
     */
    public static OpenAiCompatibleEmbedding connect(String baseUrl, String apiKey, String modelName) {
        return connect(baseUrl, apiKey, modelName, DEFAULT_BATCH_SIZE);
    }

    public static OpenAiCompatibleEmbedding connect(String baseUrl, String apiKey,
                                                    String modelName, int batchSize) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 不能为空");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正数，实际为 " + batchSize);
        }

        // 用一句话探测维度。失败就直接抛，不带着未知维度继续跑。
        OpenAiCompatibleEmbedding probe = new OpenAiCompatibleEmbedding(
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                baseUrl, apiKey, modelName, batchSize, 1);
        List<float[]> result = probe.callApi(List.of("维度探测"));
        if (result.isEmpty() || result.get(0).length == 0) {
            throw new EmbeddingApiException(
                    "接口没有返回向量，无法确定维度。请检查模型名是否正确：" + modelName, 0);
        }
        return new OpenAiCompatibleEmbedding(probe.httpClient, baseUrl, apiKey,
                modelName, batchSize, result.get(0).length);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String name() {
        return modelName + "(dim=" + dimension + ")";
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("texts 不能为 null");
        }
        if (texts.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i) == null) {
                throw new IllegalArgumentException("texts 第 " + i + " 个元素是 null");
            }
        }

        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<float[]> batch = callApi(texts.subList(start, end));
            if (batch.size() != end - start) {
                throw new EmbeddingApiException(String.format(
                        "接口返回的向量数(%d)和请求的文本数(%d)不一致，"
                                + "无法确定哪条对应哪条",
                        batch.size(), end - start), 0);
            }
            all.addAll(batch);
        }
        return List.copyOf(all);
    }

    /**
     * 发一次请求。
     *
     * <p>失败时抛 {@link EmbeddingApiException}，消息里带上 HTTP 状态码
     * 和服务端返回的原因——排查问题时这两样最关键。
     */
    private List<float[]> callApi(List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelName);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        // 刻意不传 dimensions 参数：不是所有兼容接口都支持它，
        // 传了反而可能被拒。维度由返回结果决定，见 connect()。
        // 同理也不传 encoding_format，让服务端用它自己的默认格式。

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new EmbeddingApiException("拼请求失败：" + e.getMessage(), 0, e);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EmbeddingApiException(
                    "连不上向量模型接口：" + baseUrl + "（" + e.getMessage() + "）", 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingApiException("调用向量模型接口被中断", 0, e);
        }

        if (response.statusCode() != 200) {
            throw new EmbeddingApiException(String.format(
                    "调用向量模型接口失败，HTTP %d：%s",
                    response.statusCode(), shorten(response.body())), response.statusCode());
        }

        return parseEmbeddings(response.body());
    }

    /**
     * 从返回的 JSON 里取出向量。
     *
     * <p>兼容接口的返回格式是
     * {@code {"data":[{"embedding":[...],"index":0}, ...]}}。
     *
     * <p>按 {@code index} 归位后再按顺序取——服务端不保证按请求顺序返回，
     * 不归位的话文本和向量会错位，那是静默的灾难。
     */
    private List<float[]> parseEmbeddings(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (IOException e) {
            throw new EmbeddingApiException(
                    "接口返回的不是合法 JSON：" + shorten(responseBody), 0, e);
        }

        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new EmbeddingApiException(
                    "接口返回里没有 data 数组：" + shorten(responseBody), 0);
        }

        int n = data.size();
        float[][] byIndex = new float[n][];
        List<float[]> withoutIndex = new ArrayList<>();

        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray()) {
                throw new EmbeddingApiException(
                        "返回项里没有 embedding 数组：" + shorten(item.toString()), 0);
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }

            if (item.has("index")) {
                int index = item.get("index").asInt();
                if (index >= 0 && index < n) {
                    byIndex[index] = vector;
                    continue;
                }
            }
            // 少数实现不带 index，只能按出现顺序放
            withoutIndex.add(vector);
        }

        List<float[]> result = new ArrayList<>(n);
        int fallback = 0;
        for (float[] vector : byIndex) {
            if (vector != null) {
                result.add(vector);
            } else if (fallback < withoutIndex.size()) {
                result.add(withoutIndex.get(fallback++));
            }
        }
        // 全部都没有 index 时，上面的循环一个都没放进去
        if (result.isEmpty()) {
            result.addAll(withoutIndex);
        }
        return result;
    }

    /** 报错信息里不要把整个响应体都塞进去，截断一下。 */
    private static String shorten(String text) {
        if (text == null) {
            return "(空)";
        }
        String compact = text.replaceAll("\\s+", " ").strip();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "…";
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** 模型名，供报告展示。 */
    public String modelName() {
        return modelName;
    }
}
