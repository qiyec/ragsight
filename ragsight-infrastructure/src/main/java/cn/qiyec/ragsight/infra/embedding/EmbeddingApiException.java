package cn.qiyec.ragsight.infra.embedding;

/**
 * 调向量模型接口失败时抛出。
 *
 * <p>单独定义而不是直接抛 {@code RuntimeException}，是为了让调用方能区分
 * "接口挂了"和"代码有 bug"。诊断跑一半失败时，用户看到的消息应该是
 * "调用向量模型失败，HTTP 429：超出配额"，而不是一堆堆栈。
 */
public class EmbeddingApiException extends RuntimeException {

    private final int statusCode;

    public EmbeddingApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public EmbeddingApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP 状态码；不是 HTTP 错误时返回 0。 */
    public int statusCode() {
        return statusCode;
    }
}
