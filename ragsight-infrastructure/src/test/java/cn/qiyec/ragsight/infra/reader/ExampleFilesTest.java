package cn.qiyec.ragsight.infra.reader;

import cn.qiyec.ragsight.application.GoldenCaseSetSpec;
import cn.qiyec.ragsight.application.GoldenCaseSpec;
import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.DocumentReader;
import cn.qiyec.ragsight.domain.knowledge.chunking.RecursiveCharacterChunkingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检查仓库里的示例文件是不是真的能用。
 *
 * <p><b>为什么值得单独测</b>：{@code examples/eval-format.txt} 里写着
 * "下面是一个可用的例子"，学习者很可能直接拿它跑。如果里面的 marker
 * 和语料对不上，他一上手就撞墙，而且不知道是自己写错了还是工具坏了。
 *
 * <p>这个文件真的坏过一次：marker 写的是 {@code 90%时}，
 * 而语料里是 {@code 90% 时}（中间有空格），于是整个诊断直接报错。
 * 当时没有任何测试覆盖它。
 *
 * <p>现在把它钉住：只要示例文件或语料被改动到对不上，这个测试就会红。
 */
@DisplayName("示例文件可用性")
class ExampleFilesTest {

    /** 测试的工作目录是模块目录，往上一级才是仓库根。 */
    private static final Path EXAMPLES = Path.of("../examples");

    @Test
    @DisplayName("eval-format.txt 里的例子应该能被解读成用例")
    void format_example_should_be_parseable() {
        GoldenCaseSetSpec spec = EvalFileReader.read(EXAMPLES.resolve("eval-format.txt"));

        assertThat(spec.cases())
                .as("示例文件里应该有用例")
                .isNotEmpty();

        // 至少要有正样本和负样本各一条——示例要能展示这两类
        assertThat(spec.cases().stream().filter(c -> !c.isNegative()).count())
                .as("正样本数")
                .isPositive();
        assertThat(spec.cases().stream().filter(GoldenCaseSpec::isNegative).count())
                .as("负样本数（示例里刻意留了两条，展示怎么测「本来就该答不出来」）")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("eval-format.txt 里每条 marker 都要能在示例语料里找到")
    void every_marker_in_format_example_should_match_corpus() {
        // 这个断言就是那次 bug 的复现条件：marker 和语料差一个空格，
        // materialize 会抛异常，整个诊断跑不起来。
        assertThatCodeIsCleanlyRunnable();
    }

    @Test
    @DisplayName("eval.txt 也应该能用（它是 README 里推荐的那一个）")
    void main_eval_file_should_also_work() {
        List<Chunk> chunks = loadAndChunkCorpus();
        GoldenCaseSetSpec spec = EvalFileReader.read(EXAMPLES.resolve("eval.txt"));

        // 不抛异常就算过。抛的话消息里会指明是哪个用例、哪段文字，
        // 照着改就行。
        var evaluationSet = spec.materialize(chunks);
        assertThat(evaluationSet.cases()).isNotEmpty();
    }

    @Test
    @DisplayName("marker 少一个空格就会失败——把这个坑钉住")
    void marker_with_wrong_spacing_must_fail_loudly() {
        // 语料里是"达到配额的 90% 时"，这里故意去掉空格
        GoldenCaseSetSpec bad = new GoldenCaseSetSpec("写错了的测试集",
                List.of(new GoldenCaseSpec("bad-1", "存储满了会怎样",
                        List.of("90%时"))));

        List<Chunk> chunks = loadAndChunkCorpus();

        assertThatThrownBy(() -> bad.materialize(chunks))
                .as("marker 对不上必须直接报错，不能静默跳过")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对不上")
                .hasMessageContaining("90%时");
    }

    // ------------------------------------------------------------------

    private static void assertThatCodeIsCleanlyRunnable() {
        List<Chunk> chunks = loadAndChunkCorpus();
        GoldenCaseSetSpec spec = EvalFileReader.read(EXAMPLES.resolve("eval-format.txt"));

        try {
            var evaluationSet = spec.materialize(chunks);
            assertThat(evaluationSet.cases()).isNotEmpty();
        } catch (IllegalStateException e) {
            throw new AssertionError(
                    "示例文件应该能直接用，但报错了：\n" + e.getMessage()
                            + "\n\n多半是 marker 和语料差了个空格或换行。", e);
        }
    }

    private static List<Chunk> loadAndChunkCorpus() {
        DocumentReader reader = new DirectoryDocumentReader(EXAMPLES.resolve("corpus"));
        List<Document> documents = reader.readAll();
        var strategy = new RecursiveCharacterChunkingStrategy(90, 18);

        List<Chunk> all = new ArrayList<>();
        for (Document document : documents) {
            all.addAll(strategy.chunk(document));
        }
        return List.copyOf(all);
    }
}
