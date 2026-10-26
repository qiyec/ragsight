package cn.qiyec.ragsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具返回内容的解码测试。
 *
 * <p><b>为什么需要这个</b>：AgentScope 的 {@code TextBlock.getText()}
 * 返回的不是原文，而是 JSON 编码过的字符串——带首尾引号，
 * 换行写成字面的反斜杠加 n。
 *
 * <p>这个坑很隐蔽：字段叫 {@code getText()}，看起来就该是纯文本。
 * 实际表现是工具返回的表格在日志和网页上糊成一行，
 * 中间夹着一堆 {@code \n}。我是加详细日志时发现"只有 1 行"
 * 才顺着查出来的。
 *
 * <p>解码逻辑放成包内可见就是为了能这样直接测——纯函数，
 * 边界情况多（转义嵌套、伪转义、unicode），必须逐条钉住。
 */
@DisplayName("工具返回内容解码")
class ToolResultDecodingTest {

    @Test
    @DisplayName("被 JSON 编码的文本要还原成真内容")
    void should_decode_json_encoded_text() {
        // 这是实际观测到的形态：首尾引号，换行是字面 \n
        String encoded = "\"第一行\\n第二行\\n\\n第三行\"";

        String decoded = AgentTraceHook.decodeIfJsonEncoded(encoded);

        assertThat(decoded).isEqualTo("第一行\n第二行\n\n第三行");
        assertThat(decoded).contains("\n");
        assertThat(decoded).doesNotContain("\\n");
    }

    @Test
    @DisplayName("Windows 换行 \\r\\n 也要还原")
    void should_decode_crlf() {
        String encoded = "\"甲\\r\\n乙\"";

        assertThat(AgentTraceHook.decodeIfJsonEncoded(encoded))
                .isEqualTo("甲\r\n乙");
    }

    @Test
    @DisplayName("引号转义要还原")
    void should_decode_escaped_quotes() {
        String encoded = "\"他说\\\"你好\\\"\"";

        assertThat(AgentTraceHook.decodeIfJsonEncoded(encoded))
                .isEqualTo("他说\"你好\"");
    }

    @Test
    @DisplayName("反斜杠本身要还原成一个")
    void should_decode_backslash() {
        String encoded = "\"路径 C:\\\\Users\"";

        assertThat(AgentTraceHook.decodeIfJsonEncoded(encoded))
                .isEqualTo("路径 C:\\Users");
    }

    @Test
    @DisplayName("制表符要还原")
    void should_decode_tab() {
        String encoded = "\"甲\\t乙\"";

        assertThat(AgentTraceHook.decodeIfJsonEncoded(encoded))
                .isEqualTo("甲\t乙");
    }

    @Test
    @DisplayName("没被编码的普通文本要原样返回，不能被误伤")
    void should_leave_plain_text_alone() {
        String plain = "第一行\n第二行";

        assertThat(AgentTraceHook.decodeIfJsonEncoded(plain)).isEqualTo(plain);
    }

    @Test
    @DisplayName("以引号开头但只有一半的，不当成编码文本")
    void should_not_decode_when_quote_is_unbalanced() {
        String half = "\"只有开头引号";

        assertThat(AgentTraceHook.decodeIfJsonEncoded(half)).isEqualTo(half);
    }

    @Test
    @DisplayName("空内容和 null 不该炸")
    void should_handle_empty_and_null() {
        assertThat(AgentTraceHook.decodeIfJsonEncoded(null)).isNull();
        assertThat(AgentTraceHook.decodeIfJsonEncoded("")).isEmpty();
        assertThat(AgentTraceHook.decodeIfJsonEncoded("\"\"")).isEmpty();
    }

    @Test
    @DisplayName("单独一个反斜杠结尾不能炸")
    void should_survive_trailing_backslash() {
        // 极端输入：\ 在最后，没有下一个字符可读
        assertThat(AgentTraceHook.unescapeJsonString("末尾反斜杠\\")).isEqualTo("末尾反斜杠\\");
    }

    @Test
    @DisplayName("\\u 后跟合法十六进制要还原成字符")
    void should_decode_unicode_escape() {
        // \u4e2d = 中
        assertThat(AgentTraceHook.unescapeJsonString("\\u4e2d"))
                .isEqualTo("中");
    }

    @Test
    @DisplayName("\\u 后面不是十六进制时原样保留，不抛异常")
    void should_keep_invalid_unicode_escape() {
        assertThat(AgentTraceHook.unescapeJsonString("\\uZZZZ"))
                .isEqualTo("\\uZZZZ");
    }

    @Test
    @DisplayName("真实形态：多行表格要还原成多行")
    void should_decode_multiline_table() {
        String encoded = "\"切分方案  召回方式   MRR\\r\\n"
                + "递归 90   向量      0.646\\r\\n"
                + "递归 90   关键词    0.813\\r\\n\"";

        String decoded = AgentTraceHook.decodeIfJsonEncoded(encoded);

        // 关键：真的有三行，不是一行里塞满 \r\n
        assertThat(decoded.split("\r\n")).hasSize(3);
        assertThat(decoded).contains("0.646").contains("0.813");
    }
}
