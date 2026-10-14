package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.retrieval.Retriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.qiyec.ragsight.application.DiagnosisFixtures.chunkId;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.factory;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.findingContaining;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.hasFindingContaining;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.ids;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.marker;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.plan;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.questionOf;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.scripted;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 诊断引擎的测试。
 *
 * <p><b>这组测试在检查"诊断工具自己靠不靠谱"</b>，不只是"代码能跑"。
 *
 * <p>做法是造出**已知缺陷**的场景，再看工具能不能诊断出来：
 * 故意让某个用例召回不到，它就该说"召回不够"；
 * 故意让正确答案排得很后，它就该说"瓶颈在排序"。
 *
 * <p>为什么这件事重要：诊断工具最大的风险不是功能少，而是**给出错误结论、
 * 用户还照着改了生产配置**。用已知答案的场景验证它，是它值得被信任的前提。
 */
@DisplayName("诊断引擎")
class DiagnosisEngineChunkingTest {

    /** 三种召回方案都用同一个假召回器的引擎。 */
    private static DiagnosisEngine engineWithAll(Retriever retriever) {
        return new DiagnosisEngine(factory(Map.of(
                "vector", retriever,
                "keyword", retriever,
                "hybrid", retriever)));
    }

    /** 所有问题都返回同一个片段——用来造"召回了但召回错了"。 */
    private static Retriever alwaysReturning(int caseCount, ChunkId target) {
        Map<String, List<ChunkId>> map = new LinkedHashMap<>();
        for (int i = 1; i <= caseCount; i++) {
            map.put(questionOf("c" + i), ids(target));
        }
        return scripted(map);
    }

    /** 每个用例都精确命中自己的片段。 */
    private static Retriever perfect(DiagnosisFixtures.Fixture fixture, int caseCount) {
        Map<String, List<ChunkId>> map = new LinkedHashMap<>();
        for (int i = 0; i < caseCount; i++) {
            map.put(questionOf("c" + (i + 1)), ids(fixture.expectedOf(i)));
        }
        return scripted(map);
    }

    // ---------------------------------------------------------------
    // 规则一：能不能看出"召回不够"
    // ---------------------------------------------------------------

    @Test
    @DisplayName("期望片段完全召回不到时，应指出召回不够")
    void should_report_insufficient_recall() {
        // 已知缺陷：4 个用例全都返回同一个片段，只有第一个碰巧对上
        var fixture = fixture(4);
        var retriever = alwaysReturning(4, fixture.expectedOf(0));

        var report = engineWithAll(retriever).diagnose(DiagnosisFixtures.request(fixture));

        // 召回率 = 1/4 = 0.25，应该触发"召回不够"
        assertThat(report.baseline().score("Recall@5")).isEqualTo(0.25);
        assertThat(findingContaining(report, "召回不够")).contains("召回不够");
    }

    @Test
    @DisplayName("召回够用但正确答案排得靠后时，应指出瓶颈在排序")
    void should_point_to_ranking_when_recall_is_fine_but_order_is_bad() {
        // 已知缺陷：期望片段都召回到了，但每次都排在末尾。
        // Recall 很高（都在前 5），MRR 很低（位置靠后）。
        var fixture = fixture(3);
        ChunkId wrong = fixture.fillerId();

        Map<String, List<ChunkId>> map = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            // 先给两个错的，再给对的 → 对的排第 3
            map.put(questionOf("c" + (i + 1)),
                    ids(wrong, chunkId("d", 0), fixture.expectedOf(i)));
        }

        var report = engineWithAll(scripted(map)).diagnose(DiagnosisFixtures.request(fixture));

        assertThat(report.baseline().score("Recall@5")).isEqualTo(1.0);
        assertThat(report.baseline().score("MRR@5")).isLessThan(0.5);
        // 关键断言：必须说清这是排序问题，而不是含糊地说"效果不好"
        assertThat(findingContaining(report, "排序问题")).contains("重排");
    }

    // ---------------------------------------------------------------
    // 规则二：能不能区分"值得做"和"不值得做"
    // ---------------------------------------------------------------

    @Test
    @DisplayName("换召回方式明显有收益时，应建议采用")
    void should_recommend_a_clearly_better_retriever() {
        var fixture = fixture(4);

        // 基准：全都返回同一个片段，只碰巧对上一个 → Recall 0.25
        var baselineRetriever = alwaysReturning(4, fixture.expectedOf(0));
        // 另一种召回：每个用例都精确命中 → Recall 1.0
        var betterRetriever = perfect(fixture, 4);

        var report = new DiagnosisEngine(factory(Map.of(
                "vector", baselineRetriever,
                "keyword", betterRetriever,
                "hybrid", baselineRetriever)))
                .diagnose(DiagnosisFixtures.request(fixture));

        assertThat(report.baseline().score("Recall@5")).isEqualTo(0.25);
        assertThat(report.resultOf(ExperimentId.of("p1", "keyword")).score("Recall@5"))
                .isEqualTo(1.0);

        // 收益 +0.75，远超阈值，必须建议采用
        String finding = findingContaining(report, "最值得做");
        assertThat(finding).contains("仅关键词召回");
        assertThat(finding).contains("+0.750");
    }

    @Test
    @DisplayName("换召回方式没什么收益时，应明确说不值得做")
    void should_say_a_marginal_retriever_is_not_worth_it() {
        // 两种召回表现完全一样。此时增加"融合"这套复杂度就是白花成本，
        // 工具必须说出来——这和"推荐好方案"同等重要。
        var fixture = fixture(3);
        var same = perfect(fixture, 3);

        var report = new DiagnosisEngine(factory(Map.of(
                "vector", same, "keyword", same, "hybrid", same)))
                .diagnose(DiagnosisFixtures.request(fixture));

        Map<String, Double> deltas = report.deltasAgainstBaseline()
                .get(ExperimentId.of("p1", "hybrid"));
        assertThat(deltas.values()).allSatisfy(d -> assertThat(Math.abs(d)).isLessThan(0.05));

        // 结论要说清两件事：不值得做，以及判断标准是什么
        String finding = findingContaining(report, "不值得为此增加复杂度");
        assertThat(finding).contains("0.05");
    }

    // ---------------------------------------------------------------
    // 规则三：切分该不该调
    // ---------------------------------------------------------------

    @Test
    @DisplayName("各切分方案效果差异很小时，应明确说不用在切分上花时间")
    void should_say_chunking_does_not_matter_when_spread_is_small() {
        // 两个切分方案下召回表现一样，说明切分不是瓶颈
        var fixture = fixture(3);
        var retriever = perfect(fixture, 3);

        var report = engineWithAll(retriever)
                .diagnose(DiagnosisFixtures.request(fixture,
                        List.of(plan("p1", fixture.chunks()), plan("p2", fixture.chunks()))));

        assertThat(report.chunkingCount()).isEqualTo(2);
        assertThat(findingContaining(report, "不用在切分粒度上继续花时间"))
                .contains("几乎没影响");
    }

    // ---------------------------------------------------------------
    // 测试集自身的质量提醒
    // ---------------------------------------------------------------

    @Test
    @DisplayName("测试集没有负样本时应提醒，避免分数虚高")
    void should_warn_when_no_negative_cases() {
        // 没有负样本时，一个"永远返回最相似内容"的召回器也能拿高分。
        // 工具应该主动提醒测试集不完整，而不是给个好看的数字就完事。
        var fixture = fixture(2);
        var retriever = alwaysReturning(2, fixture.expectedOf(0));

        var report = engineWithAll(retriever).diagnose(DiagnosisFixtures.request(fixture));

        assertThat(report.negativeCaseCount()).isZero();
        assertThat(findingContaining(report, "没有负样本")).contains("建议补几条");
    }

    @Test
    @DisplayName("有负样本时不应触发该提醒")
    void should_not_warn_when_negative_cases_exist() {
        var fixture = fixture(2);
        // 在正常用例后面补一个负样本（只写问题、不写期望文字）
        var caseSpec = new GoldenCaseSetSpec("测试集", List.of(
                new GoldenCaseSpec("c1", questionOf("c1"), List.of(marker("c1"))),
                new GoldenCaseSpec("neg", questionOf("neg"), List.of())));

        var request = new DiagnosisRequest(
                "测试语料",
                List.of(fixture.document()),
                caseSpec,
                List.of(plan("p1", fixture.chunks())),
                RetrieverConfig.standardBaselines());

        Map<String, List<ChunkId>> map = new LinkedHashMap<>();
        map.put(questionOf("c1"), ids(fixture.expectedOf(0)));
        map.put(questionOf("neg"), ids(fixture.fillerId()));

        var report = engineWithAll(scripted(map)).diagnose(request);

        assertThat(report.negativeCaseCount()).isEqualTo(1);
        assertThat(hasFindingContaining(report, "没有负样本")).isFalse();
    }

    // ---------------------------------------------------------------
    // 结论必须可复现
    // ---------------------------------------------------------------

    @Test
    @DisplayName("同样的输入跑两次，数字和结论必须完全一致")
    void should_be_reproducible() {
        // 这是这个工具的立身之本：结论不稳定，用户就不敢拿它改生产配置。
        var fixture = fixture(3);

        Map<String, List<ChunkId>> map = new LinkedHashMap<>();
        map.put(questionOf("c1"), ids(fixture.expectedOf(0)));
        map.put(questionOf("c2"), ids(fixture.fillerId(), fixture.expectedOf(1)));
        map.put(questionOf("c3"), ids(fixture.expectedOf(2)));

        var engine = engineWithAll(scripted(map));
        var request = DiagnosisFixtures.request(fixture);

        var first = engine.diagnose(request);
        var second = engine.diagnose(request);

        assertThat(second.findings()).isEqualTo(first.findings());
        assertThat(second.baseline().metricScores())
                .isEqualTo(first.baseline().metricScores());
        assertThat(second.deltasAgainstBaseline())
                .isEqualTo(first.deltasAgainstBaseline());
    }

    // ---------------------------------------------------------------
    // 基准与参数校验
    // ---------------------------------------------------------------

    @Test
    @DisplayName("基准取第一个切分方案 × 第一个召回方案")
    void baseline_should_be_the_first_combination() {
        var fixture = fixture(2);
        var retriever = alwaysReturning(2, fixture.expectedOf(0));

        var report = engineWithAll(retriever)
                .diagnose(DiagnosisFixtures.request(fixture,
                        List.of(plan("pA", fixture.chunks()), plan("pB", fixture.chunks()))));

        assertThat(report.baselineId()).isEqualTo(ExperimentId.of("pA", "vector"));
        assertThat(report.baseline().displayName())
                .contains("方案 pA").contains("仅向量召回");
    }

    @Test
    @DisplayName("召回方案 id 重复应被拒绝，否则报告分不清哪一行是哪个")
    void should_reject_duplicate_retriever_ids() {
        var fixture = fixture(2);

        assertThatThrownBy(() -> new DiagnosisRequest(
                "语料",
                List.of(fixture.document()),
                fixture.caseSpec(),
                List.of(plan("p1", fixture.chunks())),
                List.of(RetrieverConfig.vectorOnly(), RetrieverConfig.vectorOnly())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    @DisplayName("切分方案 id 重复应被拒绝")
    void should_reject_duplicate_chunking_ids() {
        var fixture = fixture(2);

        assertThatThrownBy(() -> new DiagnosisRequest(
                "语料",
                List.of(fixture.document()),
                fixture.caseSpec(),
                List.of(plan("p1", fixture.chunks()), plan("p1", fixture.chunks())),
                List.of(RetrieverConfig.vectorOnly())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    @DisplayName("引擎构造参数不得为空")
    void should_reject_null_factory() {
        assertThatThrownBy(() -> new DiagnosisEngine((RetrieverFactory) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("factory 不能为空");
    }

    @Test
    @DisplayName("实验次数应是切分数 × 召回数")
    void experiment_count_should_be_product_of_dimensions() {
        var fixture = fixture(2);
        var retriever = alwaysReturning(2, fixture.expectedOf(0));

        var report = engineWithAll(retriever)
                .diagnose(DiagnosisFixtures.request(fixture,
                        List.of(plan("p1", fixture.chunks()), plan("p2", fixture.chunks()))));

        assertThat(report.experimentCount()).isEqualTo(6);
        assertThat(report.chunkingCount()).isEqualTo(2);
        assertThat(report.retrieverCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("逐用例明细应保留命中位置，供定位是哪道题失败了")
    void should_keep_per_case_hit_positions() {
        var fixture = fixture(2);

        Map<String, List<ChunkId>> map = new LinkedHashMap<>();
        // c1 的期望片段排第 2
        map.put(questionOf("c1"), ids(fixture.fillerId(), fixture.expectedOf(0)));
        // c2 完全没召回到
        map.put(questionOf("c2"), ids(fixture.fillerId()));

        var report = engineWithAll(scripted(map)).diagnose(DiagnosisFixtures.request(fixture));

        assertThat(report.baseline().outcomeAt(0).bestRank()).isEqualTo(2);
        assertThat(report.baseline().outcomeAt(1).bestRank()).isEqualTo(-1);
        assertThat(report.baseline().outcomeAt(1).anyHit()).isFalse();

        // 报告要带用例 id，才能把失败原因指到具体那道题上
        assertThat(report.caseIdAt(0)).isEqualTo("c1");
        assertThat(report.caseIdAt(1)).isEqualTo("c2");
    }

    @Test
    @DisplayName("每个切分方案都要重新造检索器，避免实验之间互相污染")
    void each_chunking_should_get_its_own_factory() {
        // 真实实现里每个切分方案切出的片段不同，检索器必须按对应的片段重建。
        // 共用会让后一个覆盖前一个的索引，结果就不可信了。
        var fixture = fixture(2);
        List<Integer> corpusSizesSeen = new ArrayList<>();

        var retriever = alwaysReturning(2, fixture.expectedOf(0));
        var engine = new DiagnosisEngine(corpus -> {
            corpusSizesSeen.add(corpus.size());
            return factory(Map.of(
                    "vector", retriever,
                    "keyword", retriever,
                    "hybrid", retriever));
        });

        engine.diagnose(DiagnosisFixtures.request(fixture,
                List.of(plan("p1", fixture.chunks()), plan("p2", fixture.chunks()), plan("p3", fixture.chunks()))));

        // 三个切分方案各造一次工厂
        assertThat(corpusSizesSeen).hasSize(3);
    }

    @Test
    @DisplayName("ChunkId 的字符串形式可用于报告展示")
    void chunk_id_string_form_is_readable() {
        ChunkId id = chunkId("billing", 7);
        assertThat(id.toString()).isEqualTo("billing#7");
    }
}
