package cn.qiyec.ragsight.infra.reader;

import cn.qiyec.ragsight.application.GoldenCaseSetSpec;
import cn.qiyec.ragsight.application.GoldenCaseSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("读测试集文件")
class EvalFileReaderTest {

    @Test
    @DisplayName("应解析出问题与期望文字")
    void should_parse_question_and_markers() {
        String text = """
                Q: 账单什么时候生成
                E: 次月 3 日前生成
                E: 账单中心
                """;

        GoldenCaseSetSpec spec = EvalFileReader.parse(text, "test");

        assertThat(spec.size()).isEqualTo(1);
        GoldenCaseSpec c = spec.cases().get(0);
        assertThat(c.question()).isEqualTo("账单什么时候生成");
        assertThat(c.expectedMarkers()).containsExactly("次月 3 日前生成", "账单中心");
    }

    @Test
    @DisplayName("空行和 --- 都应切分用例")
    void blank_lines_and_dashes_should_separate_cases() {
        String text = """
                Q: 问题一
                E: 文字一

                Q: 问题二
                E: 文字二

                ---

                Q: 问题三
                E: 文字三
                """;

        GoldenCaseSetSpec spec = EvalFileReader.parse(text, "test");

        assertThat(spec.size()).isEqualTo(3);
        assertThat(spec.cases()).extracting(GoldenCaseSpec::question)
                .containsExactly("问题一", "问题二", "问题三");
    }

    @Test
    @DisplayName("用例 id 应自动生成，不用手写")
    void ids_should_be_generated() {
        String text = """
                Q: 问题一
                E: 文字一

                Q: 问题二
                E: 文字二
                """;

        GoldenCaseSetSpec spec = EvalFileReader.parse(text, "test");

        assertThat(spec.cases()).extracting(GoldenCaseSpec::id)
                .containsExactly("c001", "c002");
    }

    @Test
    @DisplayName("只有问题没有期望文字，应识别为负样本")
    void case_without_markers_should_be_negative() {
        String text = """
                Q: 手机客户端支持指纹登录吗

                Q: 正常问题
                E: 期望文字
                """;

        GoldenCaseSetSpec spec = EvalFileReader.parse(text, "test");

        assertThat(spec.size()).isEqualTo(2);
        assertThat(spec.cases().get(0).isNegative()).isTrue();
        assertThat(spec.cases().get(1).isNegative()).isFalse();
        assertThat(spec.negativeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("注释行应被忽略")
    void comments_should_be_ignored() {
        String text = """
                # 这是文件说明，不是用例
                Q: 问题
                E: 文字
                # 中间插一条注释
                """;

        GoldenCaseSetSpec spec = EvalFileReader.parse(text, "test");

        assertThat(spec.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("E 出现在任何 Q 之前应报错")
    void marker_before_any_question_should_fail() {
        assertThatThrownBy(() -> EvalFileReader.parse("E: 孤零零的期望文字", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("出现在任何 Q: 之前");
    }

    @Test
    @DisplayName("看不懂的行应报错，而不是静默忽略")
    void unrecognized_line_should_fail() {
        // 静默忽略会让用户以为用例写进去了，实际没有
        assertThatThrownBy(() -> EvalFileReader.parse(
                "Q: 问题\nE: 文字\n这行是乱写的", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("看不懂这一行");
    }

    @Test
    @DisplayName("Q 后面没写东西应报错")
    void empty_question_should_fail() {
        assertThatThrownBy(() -> EvalFileReader.parse("Q:   \nE: 文字", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("问题是空的");
    }

    @Test
    @DisplayName("E 后面没写东西应报错")
    void empty_marker_should_fail() {
        assertThatThrownBy(() -> EvalFileReader.parse("Q: 问题\nE:   ", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("E: 后面没写东西");
    }

    @Test
    @DisplayName("一个用例都没有应报错，而不是返回空测试集")
    void no_cases_should_fail() {
        // 返回空测试集会让后续指标计算除以零或得到无意义的结果，
        // 不如在这里就说清楚
        assertThatThrownBy(() -> EvalFileReader.parse("# 只有注释\n\n", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有找到任何用例");
    }

    @Test
    @DisplayName("忘了写空行分隔时，应宽容地把上一块收尾，而不是报错")
    void consecutive_questions_should_be_tolerated() {
        // 用户很容易忘写空行。直接报错虽然"严格"，但会让人以为格式错了；
        // 这里选择宽容处理：遇到新的 Q 就把上一块收掉。
        String text = """
                Q: 问题一
                E: 文字一
                Q: 问题二
                E: 文字二
                """;

        GoldenCaseSetSpec spec = EvalFileReader.parse(text, "test");

        assertThat(spec.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("应处理 UTF-8 BOM，否则第一行的 Q: 会匹配不上")
    void should_strip_bom() {
        String text = "\uFEFFQ: 问题\nE: 文字";

        GoldenCaseSetSpec spec = EvalFileReader.parse(text, "test");

        assertThat(spec.size()).isEqualTo(1);
        assertThat(spec.cases().get(0).question()).isEqualTo("问题");
    }

    @Test
    @DisplayName("应容忍 Q: 与内容之间的多余空格")
    void should_tolerate_extra_spaces() {
        GoldenCaseSetSpec spec = EvalFileReader.parse("Q:    问题  \nE:   文字  ", "test");

        assertThat(spec.cases().get(0).question()).isEqualTo("问题");
        assertThat(spec.cases().get(0).expectedMarkers()).containsExactly("文字");
    }

    @Test
    @DisplayName("解析结果应可复现")
    void should_be_reproducible() {
        String text = "Q: 问题一\nE: 文字一\n\nQ: 问题二\nE: 文字二";

        assertThat(EvalFileReader.parse(text, "t").cases())
                .isEqualTo(EvalFileReader.parse(text, "t").cases());
    }
}
