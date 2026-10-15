package cn.qiyec.ragsight.cli.command;

/**
 * 真实向量模型的默认参数。
 *
 * <p>单独放一个类而不是写成命令行选项里的字面量，是因为这些值
 * 在帮助文本和实际代码里都要用，写两份早晚会不一致。
 */
final class OpenAiCompatibleEmbeddingOptions {

    private OpenAiCompatibleEmbeddingOptions() {
    }

    /**
     * 默认接口地址：阿里 DashScope（百炼）的 OpenAI 兼容接口。
     *
     * <p>选它当默认值是因为国内直连快、不用挂代理，而且它同时支持
     * 好几种向量模型。换成别家（硅基流动、OpenAI、本地 vLLM / Ollama）
     * 只要改这个地址和模型名。
     */
    static final String DASHSCOPE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * 从哪个环境变量读 Key。
     *
     * <p>用一个统一的、属于自己的名字，而不是跟着各家供应商走
     * （DASHSCOPE_API_KEY / OPENAI_API_KEY ...）。这样换供应商时
     * 不用改环境变量名。
     */
    static final String API_KEY_ENV = "RAGSIGHT_API_KEY";
}
