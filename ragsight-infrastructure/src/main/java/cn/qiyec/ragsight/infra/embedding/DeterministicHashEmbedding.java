package cn.qiyec.ragsight.infra.embedding;

import cn.qiyec.ragsight.domain.retrieval.EmbeddingModel;
import cn.qiyec.ragsight.infra.tokenizer.TextTokenizer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 离线确定性向量化（哈希特征法 / hashing trick）。
 *
 * <p><b>它存在的唯一理由</b>：让整个检索管道可以在**没有 API Key、
 * 没有网络、没有模型文件**的环境下完整跑通并被测试。
 * 没有它，CI 和单元测试就得依赖外部服务，测试会变慢、变脆，
 * 最终被人跳过——而核心算法无人验证是最危险的状况。
 *
 * <p><b>算法</b>（经典 hashing trick，与 scikit-learn 的 HashingVectorizer 同族）：
 * <ol>
 *   <li>把文本按非字母/非表意文字切词，英文小写归一</li>
 *   <li>每个词用 CRC32 散列到 {@code [0, dimension)} 的某一维</li>
 *   <li>再用词的另一路散列决定符号（+1 / -1），用于抵消哈希碰撞带来的偏置</li>
 *   <li>累加后做 L2 归一化，使余弦相似度等价于点积</li>
 * </ol>
 *
 * <p><b>能力边界（必须诚实说明，避免被误用）</b>：
 * <ul>
 *   <li>✅ 擅长：字面重合度高的检索，与 BM25 行为接近</li>
 *   <li>❌ 不具备：语义泛化能力。"计费"和"收费"在这里是完全无关的两个向量</li>
 * </ul>
 * 因此它**可以**用于验证管道连通性、检索逻辑正确性与实验框架，
 * 但**不能**用它得出的绝对指标去代表真实 embedding 模型的表现。
 * 诊断报告必须标注实际使用的 embedding 名称，正是为了暴露这一点。
 */
public final class DeterministicHashEmbedding implements EmbeddingModel {

    /** 默认维度。取 256 是在"碰撞率可接受"与"计算/内存开销低"之间的折中。 */
    public static final int DEFAULT_DIMENSION = 256;

    private final int dimension;

    public DeterministicHashEmbedding() {
        this(DEFAULT_DIMENSION);
    }

    public DeterministicHashEmbedding(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension 必须为正数，实际为 " + dimension);
        }
        this.dimension = dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String name() {
        return "deterministic-hash(dim=" + dimension + ")";
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("texts 不能为 null");
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null) {
                throw new IllegalArgumentException("texts 中不允许出现 null 元素");
            }
            result.add(embedText(text));
        }
        return result;
    }

    private float[] embedText(String text) {
        float[] vector = new float[dimension];

        for (String token : tokenize(text)) {
            long hash = crc32(token);
            int index = (int) Math.floorMod(hash, dimension);
            // 用另一路散列决定符号：哈希碰撞时正负相消，减小系统性偏置
            float sign = ((hash >>> 32) & 1L) == 0 ? 1.0f : -1.0f;

            // 字符数越多的词信息量通常越大，用对数权重做轻微加权
            float weight = 1.0f + (float) Math.log1p(token.length());
            vector[index] += sign * weight;
        }

        l2Normalize(vector);
        return vector;
    }

    /**
     * 切词：中文按单字，英文/数字按连续片段。
     *
     * <p><b>为什么中文按单字而不是分词</b>：本项目不引入分词库（会破坏 domain 的
     * 零依赖约束，且分词器本身就是需要评估的变量）。按单字切分配合 hashing trick，
     * 对中文的字面重合检索已经足够有效。
     */
    /**
     * 切词：委托给 {@link TextTokenizer}。
     *
     * <p><b>为什么必须共用同一份切词实现</b>：BM25 也使用切词。
     * 若向量路与关键词路各写一套规则，同一份语料在两条路上会被切成不同词元，
     * 混合检索的效果就无法归因——你无法判断差异来自融合策略还是来自切词不一致。
     */
    private static List<String> tokenize(String text) {
        return TextTokenizer.tokenize(text);
    }

    /** 复用 CRC32 实例会引入共享状态，这里每次新建以保证线程安全与确定性。 */
    private static long crc32(String token) {
        CRC32 crc = new CRC32();
        crc.update(token.getBytes(StandardCharsets.UTF_8));
        long value = crc.getValue();
        // 把 32 位结果混合成一个 64 位值，供"索引"与"符号"两路使用
        return value * 0x9E3779B97F4A7C15L;
    }

    /**
     * 原地 L2 归一化。
     *
     * <p>零向量（空文本或无有效词元）保持为零向量，不做除零。
     */
    private static void l2Normalize(float[] vector) {
        double sumOfSquares = 0;
        for (float v : vector) {
            sumOfSquares += (double) v * v;
        }
        if (sumOfSquares == 0) {
            return;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }

    /**
     * 供测试与调试：暴露切词结果。
     *
     * <p>用途是定位"为什么检索不到"——当检索结果异常时，
     * 第一步应确认查询与文档是否被切成了有交集的词元。
     */
    public static List<String> tokenizeForDebug(String text) {
        return List.copyOf(tokenize(text));
    }
}
