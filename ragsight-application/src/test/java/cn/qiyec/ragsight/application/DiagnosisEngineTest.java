package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static cn.qiyec.ragsight.application.DiagnosisFixtures.chunkId;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.evalSetOf;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.factory;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.findingContaining;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.goldenCase;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.ids;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.negativeCase;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.request;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.script;
import static cn.qiyec.ragsight.application.DiagnosisFixtures.scripted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 诊断引擎的测试。
 *
 * <p><b>这组测试在验证"诊断工具本身靠不靠谱"</b>，而不只是"代码能跑"。
 *
 * <p>做法是构造**已知缺陷**的输入，然后看工具能不能诊断出来：
 * 故意让某个用例召回不到，工具就应该说"召回不足"；
 * 故意让正确答案排在后面，工具就应该说"瓶颈在排序"。
 *
 * <p>为什么这件事重要：诊断工具最大的风险不是功能少，而是**给出错误结论、
 * 用户还照着改了生产配置**。用已知答案的场景验证它，是它值得被信任的前提。
 */
@DisplayName("诊断引擎")
class DiagnosisEngineTest {

    private static final int TOP_K = 5;

    // ---------------------------------------------------------------
    // 结论规则：能不能看出"召回不足"
    // ---------------------------------------------------------------

    @Test
    @DisplayName("预期片段完全召回不到时，应指出召回不足")
    void should_report_insufficient_recall() {
        // 构造已知缺陷：测试集里 4 个用例，只召回得到 1 个。
        // 唯一的召回器对所有问题都返回 d#0。
        var evalSet = evalSetOf(
                goldenCase("c1", 0),
                goldenCase("c2", 1),
                goldenCase("c3", 2),
                goldenCase("c4", 3));

        var retriever = scripted(Map.of(
                "问题-c1", ids(chunkId("d", 0)),
                "问题-c2", ids(chunkId("d", 0)),
                "问题-c3", ids(chunkId("d", 0)),
                "问题-c4", ids(chunkId("d", 0))));

        var configs = List.of(RetrieverConfig.vectorOnly(TOP_K));
        var report = new DiagnosisEngine(factory(Map.of("vector", retriever)))
                .diagnose(request(evalSet, configs));

        // Recall@5 = 1/4 = 0.25，应触发"召回不足"
        assertThat(report.baseline().score("Recall@5")).isEqualTo(0.25);
        assertThat(findingContaining(report, "召回不足")).contains("召回不足");
    }

    @Test
    @DisplayName("召回充分但正确答案排得靠后时，应指出瓶颈在排序")
    void should_point_to_ranking_when_recall_is_fine_but_order_is_bad() {
        // 构造已知缺陷：4 个用例全都召回得到，但正确答案都排在第 5 位。
        // Recall@5 = 1.0（都在前 5），MRR@5 = 1/5 = 0.2（都很靠后）。
        var evalSet = evalSetOf(
                goldenCase("c1", 1),
                goldenCase("c2", 2),
                goldenCase("c3", 3),
                goldenCase("c4", 4));

        var retriever = scripted(Map.of(
                "问题-c1", ids(chunkId("d", 0), chunkId("d", 1), chunkId("d", 2), chunkId("d", 3), chunkId("d", 1)),
                "问题-c2", ids(chunkId("d", 0), chunkId("d", 1), chunkId("d", 2), chunkId("d", 3), chunkId("d", 2)),
                "问题-c3", ids(chunkId("d", 0), chunkId("d", 1), chunkId("d", 2), chunkId("d", 3), chunkId("d", 3)),
                "问题-c4", ids(chunkId("d", 0), chunkId("d", 1), chunkId("d", 2), chunkId("d", 0), chunkId("d", 4))));

        var configs = List.of(RetrieverConfig.vectorOnly(TOP_K));
        var report = new DiagnosisEngine(factory(Map.of("vector", retriever)))
                .diagnose(request(evalSet, configs));

        assertThat(report.baseline().score("Recall@5")).isEqualTo(1.0);
        assertThat(report.baseline().score("MRR@5")).isLessThan(0.5);
        // 关键断言：必须指出这是排序问题，而不是含糊地说"效果不好"
        assertThat(findingContaining(report, "排序问题")).contains("重排");
    }

    // ---------------------------------------------------------------
    // 结论规则：能不能区分"值得做"和"不值得做"
    // ---------------------------------------------------------------

    @Test
    @DisplayName("改进方案带来明显收益时，应建议采用")
    void should_recommend_a_clearly_better_config() {
        var evalSet = evalSetOf(
                goldenCase("c1", 1),
                goldenCase("c2", 2),
                goldenCase("c3", 3),
                goldenCase("c4", 4));

        // 基准（vector）全部召回不到（Recall=0）
        var baselineRetriever = scripted(Map.of(
                "问题-c1", ids(chunkId("d", 0)),
                "问题-c2", ids(chunkId("d", 0)),
                "问题-c3", ids(chunkId("d", 0)),
                "问题-c4", ids(chunkId("d", 0))));

        // 改进方案（keyword）全部命中且排第 1（Recall=1）
        var betterRetriever = scripted(Map.of(
                "问题-c1", ids(chunkId("d", 1)),
                "问题-c2", ids(chunkId("d", 2)),
                "问题-c3", ids(chunkId("d", 3)),
                "问题-c4", ids(chunkId("d", 4))));

        var configs = List.of(
                RetrieverConfig.vectorOnly(TOP_K),
                RetrieverConfig.keywordOnly(TOP_K));

        var report = new DiagnosisEngine(factory(Map.of(
                "vector", baselineRetriever,
                "keyword", betterRetriever)))
                .diagnose(request(evalSet, configs));

        assertThat(report.baseline().score("Recall@5")).isZero();
        assertThat(report.resultOf("keyword").score("Recall@5")).isEqualTo(1.0);

        // 收益 +1.0，远超阈值，必须建议采用，并且标出这是收益最大的一处改动
        String finding = findingContaining(report, "收益最大");
        assertThat(finding).contains("仅关键词召回");
        assertThat(finding).contains("+1.000");
    }

    @Test
    @DisplayName("改进方案没什么收益时，应明确说不值得做")
    void should_say_a_marginal_config_is_not_worth_it() {
        // 两种配置表现几乎一样。此时增加"混合检索"这套复杂度就是白花成本，
        // 工具必须说出来——这和"推荐好方案"同等重要。
        var evalSet = evalSetOf(
                goldenCase("c1", 1),
                goldenCase("c2", 2),
                goldenCase("c3", 3),
                goldenCase("c4", 4));

        var sameResult = Map.of(
                "问题-c1", ids(chunkId("d", 1)),
                "问题-c2", ids(chunkId("d", 2)),
                "问题-c3", ids(chunkId("d", 3)),
                "问题-c4", ids(chunkId("d", 4)));

        var configs = List.of(
                RetrieverConfig.vectorOnly(TOP_K),
                RetrieverConfig.hybridRrf(TOP_K));

        var report = new DiagnosisEngine(factory(Map.of(
                "vector", scripted(sameResult),
                "hybrid", scripted(sameResult))))
                .diagnose(request(evalSet, configs));

        Map<String, Double> deltas = report.deltasAgainstBaseline().get("hybrid");
        assertThat(deltas.values()).allSatisfy(d -> assertThat(Math.abs(d)).isLessThan(0.05));

        // 结论必须说清楚两件事：不值得做，以及判断标准是什么
        String finding = findingContaining(report, "不值得为此增加复杂度");
        assertThat(finding).contains("建议不要采用");
        assertThat(finding).contains("0.05");
    }

    // ---------------------------------------------------------------
    // 测试集自身的质量提醒
    // ---------------------------------------------------------------

    @Test
    @DisplayName("测试集没有负样本时应提醒，避免分数虚高")
    void should_warn_when_eval_set_has_no_negative_cases() {
        // 没有负样本时，一个"永远返回最相似内容"的召回器也能拿高分。
        // 工具应该主动提醒测试集不完整，而不是给出一个好看的数字就完事。
        var evalSet = evalSetOf(goldenCase("c1", 1), goldenCase("c2", 2));

        var retriever = scripted(Map.of(
                "问题-c1", ids(chunkId("d", 1)),
                "问题-c2", ids(chunkId("d", 2))));

        var report = new DiagnosisEngine(factory(Map.of("vector", retriever)))
                .diagnose(request(evalSet, List.of(RetrieverConfig.vectorOnly(TOP_K))));

        assertThat(report.negativeCaseCount()).isZero();
        assertThat(findingContaining(report, "不含负样本")).contains("建议补充");
    }

    @Test
    @DisplayName("有负样本时不应触发该提醒")
    void should_not_warn_when_negative_cases_exist() {
        var evalSet = evalSetOf(goldenCase("c1", 1), negativeCase("neg"));

        var retriever = scripted(Map.of(
                "问题-c1", ids(chunkId("d", 1)),
                "问题-neg", ids(chunkId("d", 0))));

        var report = new DiagnosisEngine(factory(Map.of("vector", retriever)))
                .diagnose(request(evalSet, List.of(RetrieverConfig.vectorOnly(TOP_K))));

        assertThat(report.negativeCaseCount()).isEqualTo(1);
        assertThat(report.findings()).noneMatch(f -> f.contains("不含负样本"));
    }

    // ---------------------------------------------------------------
    // 结论必须可复现
    // ---------------------------------------------------------------

    @Test
    @DisplayName("同样的输入跑两次，报告里的数字和结论必须完全一致")
    void should_be_reproducible() {
        // 这是整个工具的立身之本：结论不稳定，用户就不敢拿它改生产配置。
        var evalSet = evalSetOf(goldenCase("c1", 1), goldenCase("c2", 2));

        var retriever = scripted(Map.of(
                "问题-c1", ids(chunkId("d", 1)),
                "问题-c2", ids(chunkId("d", 0), chunkId("d", 2))));

        var configs = List.of(
                RetrieverConfig.vectorOnly(TOP_K),
                RetrieverConfig.keywordOnly(TOP_K));

        var engine = new DiagnosisEngine(factory(Map.of(
                "vector", retriever,
                "keyword", retriever)));

        var first = engine.diagnose(request(evalSet, configs));
        var second = engine.diagnose(request(evalSet, configs));

        assertThat(second.findings()).isEqualTo(first.findings());
        assertThat(second.baseline().metricScores())
                .isEqualTo(first.baseline().metricScores());
        assertThat(second.deltasAgainstBaseline())
                .isEqualTo(first.deltasAgainstBaseline());
    }

    // ---------------------------------------------------------------
    // 参数校验
    // ---------------------------------------------------------------

    @Test
    @DisplayName("基准配置取第一个——调用方应把现状放第一位")
    void baseline_should_be_the_first_config() {
        var evalSet = evalSetOf(goldenCase("c1", 1));

        var retriever = scripted(Map.of("问题-c1", ids(chunkId("d", 1))));

        var configs = List.of(
                RetrieverConfig.hybridRrf(TOP_K),
                RetrieverConfig.vectorOnly(TOP_K));

        var report = new DiagnosisEngine(factory(Map.of(
                "hybrid", retriever,
                "vector", retriever)))
                .diagnose(request(evalSet, configs));

        assertThat(report.baselineConfigId()).isEqualTo("hybrid");
    }

    @Test
    @DisplayName("配置 id 重复应被拒绝，否则报告无法区分是哪一行")
    void should_reject_duplicate_config_ids() {
        var evalSet = evalSetOf(goldenCase("c1", 1));
        var duplicated = List.of(
                RetrieverConfig.vectorOnly(TOP_K),
                RetrieverConfig.vectorOnly(TOP_K));

        assertThatThrownBy(() -> request(evalSet, duplicated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    @DisplayName("引擎构造参数不得为空")
    void should_reject_null_factory() {
        assertThatThrownBy(() -> new DiagnosisEngine(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("factory 不能为空");
    }

    @Test
    @DisplayName("逐用例明细应保留每个用例的命中位置，供定位失败原因")
    void should_keep_per_case_hit_positions() {
        var evalSet = evalSetOf(goldenCase("c1", 2), goldenCase("c2", 3));

        var retriever = scripted(Map.of(
                "问题-c1", ids(chunkId("d", 0), chunkId("d", 2)),
                "问题-c2", ids(chunkId("d", 0))));

        var report = new DiagnosisEngine(factory(Map.of("vector", retriever)))
                .diagnose(request(evalSet, List.of(RetrieverConfig.vectorOnly(TOP_K))));

        // c1 的期望片段排在第 2 → 命中位置为 2
        assertThat(report.baseline().outcomeAt(0).bestRank()).isEqualTo(2);
        // c2 完全没召回到 → -1
        assertThat(report.baseline().outcomeAt(1).bestRank()).isEqualTo(-1);
        assertThat(report.baseline().outcomeAt(1).anyHit()).isFalse();

        // 报告要带上用例 id，才能把失败原因指到具体问题上
        assertThat(report.caseIdAt(0)).isEqualTo("c1");
        assertThat(report.caseIdAt(1)).isEqualTo("c2");
    }

    @Test
    @DisplayName("每个配置必须使用独立的召回器，避免实验之间互相污染")
    void each_config_should_get_its_own_retriever() {
        // 真实实现里每个配置要独立建索引。如果共用同一个实例，
        // 后一个配置会覆盖前一个的索引，导致结果不可信。
        // 这里用计数的方式确认工厂对每个配置都调用了一次。
        var evalSet = evalSetOf(goldenCase("c1", 1));
        List<String> createdConfigIds = new java.util.ArrayList<>();

        var retriever = scripted(Map.of("问题-c1", ids(chunkId("d", 1))));
        RetrieverFactory countingFactory = config -> {
            createdConfigIds.add(config.id());
            return retriever;
        };

        var configs = List.of(
                RetrieverConfig.vectorOnly(TOP_K),
                RetrieverConfig.keywordOnly(TOP_K),
                RetrieverConfig.hybridRrf(TOP_K));

        new DiagnosisEngine(countingFactory).diagnose(request(evalSet, configs));

        assertThat(createdConfigIds).containsExactly("vector", "keyword", "hybrid");
    }

    @Test
    @DisplayName("ChunkId 的字符串形式可用于报告展示")
    void chunk_id_string_form_is_readable() {
        ChunkId id = chunkId("billing", 7);
        assertThat(id.toString()).isEqualTo("billing#7");
    }
}
