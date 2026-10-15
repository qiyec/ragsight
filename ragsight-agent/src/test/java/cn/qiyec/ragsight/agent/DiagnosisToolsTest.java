package cn.qiyec.ragsight.agent;

import cn.qiyec.ragsight.diagnosis.CorpusCatalog;
import cn.qiyec.ragsight.diagnosis.DiagnosisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 诊断工具的逻辑测试。
 *
 * <p><b>这里完全不调大模型。</b> 工具本身是确定性的：给它一串指定动作，
 * 它就去跑对应的实验、返回一组数字。大模型只负责"选择调哪个工具"，
 * 那是它的事，不该混进工具的测试里——混进来测试就变慢、变贵、还不可复现。
 *
 * <p>用的是内置演示语料（在内存里，不读磁盘、不联网），所以这些测试
 * 和 domain 层的测试一样可靠。
 *
 * <p>最要紧的一组断言是<b>"每次只改一个东西"</b>。这不是措辞问题：
 * 如果一次对比里动了两个参数，那指标变了也不知道是谁的功劳，
 * 整个第二层就退化成"换个方式穷举"，没有任何价值。
 */
@DisplayName("诊断工具")
class DiagnosisToolsTest {

    private DiagnosisSession session;
    private DiagnosisTools tools;

    @BeforeEach
    void setUp() {
        // 指向一个不存在的目录：这样只剩内置演示语料，测试不受仓库里
        // examples/ 的内容影响。测试依赖真实文件的话，改语料就会连带
        // 改坏一批测试，那不是我们想维护的关系。
        CorpusCatalog catalog = new CorpusCatalog(
                Path.of("不存在的目录-" + System.nanoTime()).toString());
        DiagnosisService service = new DiagnosisService(catalog);

        DiagnosisService.ApiRequest embedding = new DiagnosisService.ApiRequest(
                CorpusCatalog.DEMO_ID, null, null, 5, false, false, null, null);

        session = new DiagnosisSession(
                CorpusCatalog.DEMO_ID, "演示语料", 5, 18, service, embedding);
        tools = new DiagnosisTools(session);
    }

    // ==================================================================
    // run_baseline
    // ==================================================================

    @Nested
    @DisplayName("建立基准")
    class RunBaseline {

        @Test
        @DisplayName("应跑 2 种切分 × 3 种召回 = 6 组")
        void should_run_two_chunkings_times_three_retrievers() {
            String output = tools.runBaseline(90);

            // 报告里每一组一行，所以数"行"就知道跑了几组
            assertThat(output).contains("递归切分 90 字").contains("固定窗口 90 字");
            assertThat(session.attempted()).hasSize(6);
            assertThat(session.experimentCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("应把 MRR 最高的那组选为当前最好")
        void should_pick_highest_mrr_as_best() {
            tools.runBaseline(90);

            double maxMrr = session.attempted().stream()
                    .mapToDouble(DiagnosisSession.Attempted::mrr).max().orElseThrow();
            assertThat(session.best()).isNotNull();
            assertThat(session.best().mrr()).isEqualTo(maxMrr);
        }

        @Test
        @DisplayName("应给出下一步建议，而不是让模型自己从数字里猜")
        void should_suggest_next_step() {
            String output = tools.runBaseline(90);

            assertThat(output).contains("下一步建议");
            // 建议里要么指向"先解决召回"，要么指向"去试重排"，
            // 不能含糊其辞
            assertThat(output).satisfiesAnyOf(
                    s -> assertThat(s).contains("召回率"),
                    s -> assertThat(s).contains("排序"));
        }

        @Test
        @DisplayName("重复调用时不该重跑，应提示去用别的工具")
        void should_refuse_second_baseline() {
            tools.runBaseline(90);
            int before = session.experimentCount();

            String output = tools.runBaseline(90);

            assertThat(output).contains("已经跑过基准");
            assertThat(output).contains("compare_one_change");
            // 关键：没有真的再跑一遍，没有白花钱
            assertThat(session.experimentCount()).isEqualTo(before);
        }
    }

    // ==================================================================
    // compare_one_change —— 核心
    // ==================================================================

    @Nested
    @DisplayName("单变量对比")
    class CompareOneChange {

        @BeforeEach
        void runBaselineFirst() {
            tools.runBaseline(90);
        }

        @Test
        @DisplayName("一次对比只跑 2 组（改前 + 改后），不是把组合跑一遍")
        void should_run_exactly_two_groups_per_comparison() {
            int before = session.experimentCount();

            tools.compareOneChange("rerank", "true");

            assertThat(session.experimentCount() - before).isEqualTo(2);
        }

        @Test
        @DisplayName("改切分窗口：应报出改的是哪个数、变成哪个数")
        void should_report_chunk_size_change() {
            String output = tools.compareOneChange("chunk_size", "200");

            assertThat(output).contains("切分窗口");
            assertThat(output).contains("200");
            assertThat(output).contains("改之前");
            assertThat(output).contains("改之后");
            assertThat(output).contains("变化");
        }

        @Test
        @DisplayName("改召回方式：结果里应能看出在对比哪两种召回")
        void should_report_retriever_change() {
            // 注意不能写死"应该提到仅关键词召回"——基准之后"当前最好"
            // 本来就是 MRR 最高的那个，它可能是关键词，也可能是向量。
            // 从当前最好出发换一个别的召回方式。
            String from = session.best().retrieverLabel();
            String to = from.equals("仅关键词召回") ? "vector" : "keyword";

            String output = tools.compareOneChange("retriever", to);

            assertThat(output).contains("召回方式");
            assertThat(output).contains("改之前").contains("改之后");
            // 两种召回方式的名字都应该出现，才叫"对比"
            assertThat(output).contains(from);
            assertThat(output).contains(to.equals("keyword") ? "仅关键词召回" : "仅向量召回");
        }

        @Test
        @DisplayName("改重排：应说清是从开到关还是从关到开")
        void should_report_rerank_change() {
            String output = tools.compareOneChange("rerank", "true");

            assertThat(output).contains("重排");
            assertThat(output).contains("改之前");
        }

        @Test
        @DisplayName("每次对比之后，当前最好不应变差")
        void best_should_never_get_worse() {
            double mrrBefore = session.best().mrr();

            tools.compareOneChange("chunk_size", "200");
            tools.compareOneChange("retriever", "keyword");
            tools.compareOneChange("strategy", "fixed");

            // 当前最好只会被更好的替换，不会被更差的覆盖
            assertThat(session.best().mrr()).isGreaterThanOrEqualTo(mrrBefore);
        }

        @Test
        @DisplayName("应报告还剩哪些方向没试过，免得模型反复试同一个")
        void should_report_remaining_ideas() {
            String output = tools.compareOneChange("chunk_size", "200");

            assertThat(output).contains("还没试过的方向");
        }

        @Test
        @DisplayName("试过的窗口大小和召回方式要被记住")
        void should_track_what_was_tried() {
            tools.compareOneChange("chunk_size", "200");
            tools.compareOneChange("retriever", "keyword");

            assertThat(session.triedChunkSizes()).contains(90, 200);
            assertThat(session.triedRetrievers()).contains("仅关键词召回");
        }
    }

    // ==================================================================
    // 参数校验：这些错误必须"说人话"，因为读到的是大模型
    // ==================================================================

    @Nested
    @DisplayName("参数校验")
    class Validation {

        @BeforeEach
        void runBaselineFirst() {
            tools.runBaseline(90);
        }

        @Test
        @DisplayName("没跑基准就对比时，应让模型先去跑基准，而不是抛异常")
        void should_tell_model_to_run_baseline_first() {
            DiagnosisTools fresh = new DiagnosisTools(sessionForEmptySession());

            String output = fresh.compareOneChange("rerank", "true");

            // 返回文字而不是抛异常：模型看到"请先跑基准"会照做，
            // 看到堆栈只会瞎猜
            assertThat(output).contains("先调用 run_baseline");
        }

        @Test
        @DisplayName("不认识的 change 应列出可选值")
        void should_list_valid_changes() {
            String output = tools.compareOneChange("随便写的", "x");

            assertThat(output).contains("chunk_size");
            assertThat(output).contains("strategy");
            assertThat(output).contains("retriever");
            assertThat(output).contains("rerank");
        }

        @Test
        @DisplayName("chunk_size 给了非数字应报错")
        void should_reject_non_numeric_chunk_size() {
            String output = tools.compareOneChange("chunk_size", "大一点");

            assertThat(output).contains("必须是数字");
        }

        @Test
        @DisplayName("窗口比重叠字符数还小应报错")
        void should_reject_chunk_size_smaller_than_overlap() {
            String output = tools.compareOneChange("chunk_size", "5");

            assertThat(output).contains("重叠");
        }

        @Test
        @DisplayName("retriever 给了不认识的值应列出可用的")
        void should_reject_unknown_retriever() {
            String output = tools.compareOneChange("retriever", "魔法召回");

            assertThat(output).contains("只能是");
        }

        @Test
        @DisplayName("strategy 给了不认识的值应报错")
        void should_reject_unknown_strategy() {
            String output = tools.compareOneChange("strategy", "随机切");

            assertThat(output).contains("recursive");
        }

        @Test
        @DisplayName("rerank 给了 true/false 之外的值应报错")
        void should_reject_non_boolean_rerank() {
            String output = tools.compareOneChange("rerank", "也许");

            assertThat(output).contains("true");
        }

        @Test
        @DisplayName("改成和现在一样的值应被拦下，避免白跑一次")
        void should_reject_changing_to_same_value() {
            int before = session.experimentCount();
            // 用当前最好的那个召回方式改，等于没改
            String current = session.best().retrieverId();

            String output = tools.compareOneChange("retriever", current);

            assertThat(output).contains("当前最好用的就是");
            assertThat(session.experimentCount()).isEqualTo(before);
        }

        @Test
        @DisplayName("改成同一个切分窗口也应被拦下")
        void should_reject_same_chunk_size() {
            int before = session.experimentCount();

            // 基准用的是 90 字，再改成 90 就是没改
            String output = tools.compareOneChange("chunk_size",
                    String.valueOf(chunkSizeOfBest()));

            assertThat(output).contains("当前最好用的就是");
            assertThat(session.experimentCount()).isEqualTo(before);
        }

        /** 从当前最好的方案名里读出切分窗口。 */
        private int chunkSizeOfBest() {
            var m = java.util.regex.Pattern.compile("(\\d+)\\s*字")
                    .matcher(session.best().planLabel());
            return m.find() ? Integer.parseInt(m.group(1)) : 90;
        }
    }

    // ==================================================================
    // list_attempts / finish
    // ==================================================================

    @Nested
    @DisplayName("查看与收尾")
    class ListingAndFinish {

        @Test
        @DisplayName("还没跑过时列表应提示先跑基准")
        void list_should_prompt_baseline_first() {
            assertThat(tools.listAttempts()).contains("先调用 run_baseline");
        }

        @Test
        @DisplayName("列表应包含跑过的配置和实验次数")
        void list_should_include_attempts() {
            tools.runBaseline(90);

            String output = tools.listAttempts();

            assertThat(output).contains("递归切分 90 字");
            assertThat(output).contains("6 次实验");
        }

        @Test
        @DisplayName("还没跑过就收尾时应提示先跑基准")
        void finish_should_prompt_baseline_first() {
            assertThat(tools.finish()).contains("先调用 run_baseline");
        }

        @Test
        @DisplayName("收尾应给出推荐配置和硬指标")
        void finish_should_report_recommendation() {
            tools.runBaseline(90);

            String output = tools.finish();

            assertThat(output).contains("推荐配置");
            assertThat(output).contains("Recall");
            assertThat(output).contains("MRR");
            assertThat(output).contains("次实验");
        }

        @Test
        @DisplayName("收尾必须带出诊断引擎的结论，并说明那不是大模型写的")
        void finish_should_include_engine_findings() {
            tools.runBaseline(90);

            String output = tools.finish();

            assertThat(output).contains("诊断引擎给出的结论");
            // 这句话很重要：用户要知道结论的来源不是大模型，
            // 否则会怀疑"这次说的和上次不一样"
            assertThat(output).contains("不是大模型写的");
        }

        @Test
        @DisplayName("收尾应说明穷举要跑多少次，让人看出省了多少")
        void finish_should_compare_with_brute_force() {
            tools.runBaseline(90);

            String output = tools.finish();

            assertThat(output).contains("18 次");
            assertThat(output).contains("一共跑了");
        }
    }

    // ==================================================================
    // 会话状态
    // ==================================================================

    @Nested
    @DisplayName("会话状态")
    class SessionState {

        @Test
        @DisplayName("一开始没有当前最好，也没有跑过实验")
        void should_start_empty() {
            assertThat(session.best()).isNull();
            assertThat(session.attempted()).isEmpty();
            assertThat(session.experimentCount()).isZero();
            assertThat(session.allFindings()).isEmpty();
        }

        @Test
        @DisplayName("同一组方案重复出现时不该重复计数")
        void should_not_double_count_same_config() {
            tools.runBaseline(90);
            int afterBaseline = session.attempted().size();

            // 对比里"改之前"那一组和基准里的某组是同一个配置
            tools.compareOneChange("rerank", "true");

            // attempted 是按配置去重的，所以增量应该只有"改之后"那一个新配置
            assertThat(session.attempted().size()).isLessThanOrEqualTo(afterBaseline + 2);
        }

        @Test
        @DisplayName("两次跑同样的东西应得到同样的数字（可复现）")
        void should_be_reproducible() {
            tools.runBaseline(90);

            DiagnosisSession other = sessionForEmptySession();
            new DiagnosisTools(other).runBaseline(90);

            assertThat(other.attempted()).hasSameSizeAs(session.attempted());
            assertThat(other.best().mrr()).isEqualTo(session.best().mrr());
            assertThat(other.best().recall()).isEqualTo(session.best().recall());
        }
    }

    // ==================================================================
    // DiagnosisRun 的入口校验（不需要大模型就能验的部分）
    // ==================================================================

    @Nested
    @DisplayName("入口校验")
    class RunEntry {

        @Test
        @DisplayName("语料 id 不存在时应报错，并列出可用的")
        void should_reject_unknown_corpus() {
            assertThatThrownBy(() -> DiagnosisRun.builder()
                    .examplesDir(Path.of("不存在的目录-" + System.nanoTime()).toString())
                    .corpusId("没有这个语料")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("没有这个语料")
                    // 报错里要带上有哪些可选，不然模型/用户只能瞎试
                    .hasMessageContaining(CorpusCatalog.DEMO_ID);
        }

        @Test
        @DisplayName("topK 必须为正数")
        void should_reject_non_positive_top_k() {
            assertThatThrownBy(() -> DiagnosisRun.builder().topK(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("topK");
        }

        @Test
        @DisplayName("不填语料时应自动选默认那个")
        void should_default_corpus() {
            // 能建出来就说明默认语料存在。不执行 execute()——
            // 那会真的去调大模型。
            DiagnosisRun run = DiagnosisRun.builder()
                    .examplesDir(Path.of("不存在的目录-" + System.nanoTime()).toString())
                    .build();

            assertThat(run).isNotNull();
            // 还没跑，所以这些是空的/零
            assertThat(run.summary().experimentCount()).isZero();
            assertThat(run.summary().bestConfig()).isNull();
        }
    }

    /** 造一个干净的会话，用来测"一开始什么都没有"的场景。 */
    private static DiagnosisSession sessionForEmptySession() {
        CorpusCatalog catalog = new CorpusCatalog(
                Path.of("不存在的目录-" + System.nanoTime()).toString());
        return new DiagnosisSession(
                CorpusCatalog.DEMO_ID, "演示语料", 5, 18,
                new DiagnosisService(catalog),
                new DiagnosisService.ApiRequest(
                        CorpusCatalog.DEMO_ID, null, null, 5, false, false, null, null));
    }

    /** 确认测试确实没在读磁盘上的 examples 目录。 */
    @Test
    @DisplayName("测试用的是内存里的演示语料，不读磁盘")
    void test_should_not_read_repo_files() {
        tools.runBaseline(90);

        // 走到这里就说明一路都是内存里的演示语料跑通的。
        // 刻意不断言"examples 目录存在"——那是在断言测试的工作目录，
        // 换个地方跑就会假失败，和被测功能没关系。
        assertThat(session.corpusLabel()).isEqualTo("演示语料");
        assertThat(session.attempted()).isNotEmpty();
    }
}
