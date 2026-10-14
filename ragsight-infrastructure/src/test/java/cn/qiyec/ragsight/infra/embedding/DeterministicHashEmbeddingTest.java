package cn.qiyec.ragsight.infra.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("离线确定性向量化")
class DeterministicHashEmbeddingTest {

    private final DeterministicHashEmbedding embedding = new DeterministicHashEmbedding(256);

    @Test
    @DisplayName("维度非法应被拒绝")
    void should_reject_invalid_dimension() {
        assertThatThrownBy(() -> new DeterministicHashEmbedding(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension 必须为正数");
    }

    @Test
    @DisplayName("向量维度必须等于声明维度")
    void should_produce_declared_dimension() {
        float[] v = embedding.embedOne("计费规则的说明");

        assertThat(v).hasSize(256);
        assertThat(embedding.dimension()).isEqualTo(256);
    }

    @Test
    @DisplayName("同一文本两次编码必须完全一致（可复现性，R5）")
    void should_be_deterministic() {
        float[] first = embedding.embedOne("数据库连接池配置说明");
        float[] second = embedding.embedOne("数据库连接池配置说明");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("向量应做 L2 归一化，模长约为 1")
    void should_normalize_to_unit_length() {
        float[] v = embedding.embedOne("计费规则与账单说明");

        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("相同文本的余弦相似度应为 1")
    void identical_text_should_have_similarity_one() {
        float[] a = embedding.embedOne("计费规则的说明");
        float[] b = embedding.embedOne("计费规则的说明");

        assertThat(cosine(a, b)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("字面重合度高的文本，相似度应高于无关文本")
    void overlapping_text_should_be_more_similar() {
        // 这是本实现能力边界的核心断言：只擅长字面重合
        float[] query = embedding.embedOne("计费规则如何计算");
        float[] related = embedding.embedOne("计费规则的计算方式说明");
        float[] unrelated = embedding.embedOne("用户登录鉴权流程");

        assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated));
    }

    @Test
    @DisplayName("语义相近但用词不同的文本得不到高分——精确记录能力边界")
    void semantically_similar_but_lexically_different_is_not_guaranteed() {
        // 「计费」与「收费」语义近乎相同，但**没有共同的字符**。
        // 哈希特征法只认字面，因此给出 0.5——这个分数与"部分无关"的文本对
        // （如 计费规则/收费规则 之外的组合）处在同一量级，无法据此认为它们相关。
        //
        // 这条测试把能力边界固化成可执行的断言。若将来换成真实 embedding，
        // 它会失败——那时应当**重新审视**这条断言，而不是当作"实现变好了"删掉：
        // 它记录的正是"离线实现不能代表真实模型效果"这一事实。
        float[] billing = embedding.embedOne("计费");
        float[] charging = embedding.embedOne("收费");

        assertThat(cosine(billing, charging)).isLessThan(0.6);
    }

    @Test
    @DisplayName("共享字面则相似度按共享比例上升——与其能力边界对应")
    void shared_characters_should_drive_similarity() {
        // 「计费规则」与「收费规则」共享「费」「规」「则」3 字（共 4 字），
        // 实测余弦为 0.75。这是正确的：字面重合是它唯一的信息来源。
        float[] a = embedding.embedOne("计费规则");
        float[] b = embedding.embedOne("收费规则");

        assertThat(cosine(a, b)).isCloseTo(0.75, within(0.01));
    }

    @Test
    @DisplayName("完全无共同词元的文本，相似度精确为 0")
    void disjoint_text_should_have_zero_similarity() {
        // 无任何共同字符、且维度足够大时哈希碰撞极少，
        // 因此表现为精确的 0（而非"接近 0"）。
        float[] a = embedding.embedOne("计费规则");
        float[] b = embedding.embedOne("天气晴朗");

        assertThat(cosine(a, b)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("批量编码顺序必须与输入严格一致")
    void batch_should_preserve_input_order() {
        List<String> texts = List.of("第一段计费说明", "第二段登录说明", "第三段配置说明");

        List<float[]> batch = embedding.embed(texts);

        assertThat(batch).hasSize(3);
        for (int i = 0; i < texts.size(); i++) {
            assertThat(batch.get(i)).isEqualTo(embedding.embedOne(texts.get(i)));
        }
    }

    @Test
    @DisplayName("空文本应产生零向量，而不是 NaN")
    void empty_text_should_produce_zero_vector() {
        float[] v = embedding.embedOne("");

        assertThat(v).hasSize(256);
        for (float x : v) {
            assertThat(x).isZero();
        }
    }

    @Test
    @DisplayName("纯标点文本也应产生零向量（无有效词元）")
    void punctuation_only_should_produce_zero_vector() {
        float[] v = embedding.embedOne("，。！？；：");

        for (float x : v) {
            assertThat(x).isZero();
        }
    }

    @Test
    @DisplayName("null 输入应被拒绝")
    void should_reject_null_input() {
        assertThatThrownBy(() -> embedding.embedOne(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> embedding.embed(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> embedding.embed(java.util.Arrays.asList("ok", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null 元素");
    }

    // ---------- 切词行为 ----------

    @Test
    @DisplayName("中文应按单字切分")
    void should_tokenize_chinese_per_character() {
        List<String> tokens = DeterministicHashEmbedding.tokenizeForDebug("计费规则");

        assertThat(tokens).containsExactly("计", "费", "规", "则");
    }

    @Test
    @DisplayName("英文应按连续片段切分并转小写")
    void should_tokenize_latin_as_words_lowercased() {
        List<String> tokens = DeterministicHashEmbedding.tokenizeForDebug("Pool Size");

        assertThat(tokens).containsExactly("pool", "size");
    }

    @Test
    @DisplayName("中英混排应按类型正确切换")
    void should_tokenize_mixed_text() {
        List<String> tokens = DeterministicHashEmbedding.tokenizeForDebug("连接池Pool配置");

        assertThat(tokens).containsExactly("连", "接", "池", "pool", "配", "置");
    }

    @Test
    @DisplayName("标点与空白应被丢弃")
    void should_drop_punctuation_and_whitespace() {
        List<String> tokens = DeterministicHashEmbedding.tokenizeForDebug("计费，规则。 a-b");

        assertThat(tokens).containsExactly("计", "费", "规", "则", "a", "b");
    }

    @Test
    @DisplayName("名称应包含维度，供诊断报告区分不同配置")
    void name_should_include_dimension() {
        assertThat(new DeterministicHashEmbedding(256).name()).contains("256");
        assertThat(new DeterministicHashEmbedding(512).name())
                .isNotEqualTo(new DeterministicHashEmbedding(256).name());
    }

    @Test
    @DisplayName("不同维度之间不应互相影响（同一维度内确定性）")
    void different_dimensions_are_independent() {
        float[] v256a = new DeterministicHashEmbedding(256).embedOne("计费规则");
        float[] v256b = new DeterministicHashEmbedding(256).embedOne("计费规则");

        assertThat(v256a).isEqualTo(v256b);
        assertThat(new DeterministicHashEmbedding(512).embedOne("计费规则")).hasSize(512);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
