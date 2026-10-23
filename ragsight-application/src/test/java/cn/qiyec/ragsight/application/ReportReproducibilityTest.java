package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.retrieval.Retriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告可复现性的测试。
 *
 * <p>这是项目的底线（AGENTS.md 里的 R5）：**同一份输入，跑两次必须得到
 * 同一份报告**。用户要拿报告去改生产配置，报告本身不稳定他就不敢用。
 *
 * <p>这里专门盯一个踩过的坑：**指标列的顺序曾经不稳定**。
 *
 * <p>原因是 {@code ExperimentResult.metricScores} 用了 {@code Map.copyOf}，
 * 那个方法只保证不可变、**不保证保留插入顺序**。
 * 而报告的列顺序正是从这个 Map 的 key 顺序来的
 * （见 {@code DiagnosisReport.metricNames()}）。
 *
 * <p>实测连续跑 5 次，出现过 3 种列顺序：
 * <pre>
 *   Recall / MRR / nDCG
 *   MRR / Recall / nDCG
 *   nDCG / Recall / MRR
 * </pre>
 *
 * <p>数字本身不会错配（表头和每一行用的是同一个列表，一起变），
 * 但"同一份数据两次生成的报告长得不一样"已经违反了可复现这条底线。
 * 这种 bug 特别难发现：不报错、数字还是对的，只有把两次输出并排看才看得出来。
 */
@DisplayName("报告可复现性")
class ReportReproducibilityTest {

    @Test
    @DisplayName("连续跑多次，指标列顺序必须完全一致")
    void metric_column_order_should_be_stable() {
        Set<List<String>> seen = new LinkedHashSet<>();

        for (int i = 0; i < 5; i++) {
            seen.add(runOnce().metricNames());
        }

        assertThat(seen)
                .as("五次运行出现了 %d 种列顺序：%s", seen.size(), seen)
                .hasSize(1);
    }

    @Test
    @DisplayName("指标列的顺序要和引擎算的顺序一致，不能靠运气")
    void metric_order_should_follow_engine_order() {
        List<String> names = runOnce().metricNames();

        // DiagnosisEngine 里指标是按 Recall、MRR、nDCG 这个顺序算的。
        // 报告的列就该按这个顺序排。
        assertThat(names).hasSize(3);
        assertThat(names.get(0)).startsWith("Recall");
        assertThat(names.get(1)).startsWith("MRR");
        assertThat(names.get(2)).startsWith("nDCG");
    }

    @Test
    @DisplayName("上面那条测试才是真正抓 bug 的，跑多次那种抓不住")
    void stable_across_runs_is_a_weak_check() {
        // 实测过：把修复撤掉（改回 Map.copyOf）之后，
        // 「跑 5 次看是否一致」那条测试**依然通过**——
        // 因为同一个 JVM 里，同一个 key set 的 HashMap 迭代顺序
        // 通常是不变的。真正跨进程才会变。
        //
        // 也就是说：**"跑多次看是否一致"这种测试在同进程内抓不住
        // 不保序的 bug**，只有"断言期望的具体顺序"才抓得住。
        //
        // 这里刻意留一条测试把这个认识钉住，免得以后有人觉得
        // 前一条是冗余的、把它删了——删了就等于把真正的门禁删了。
        List<String> names = runOnce().metricNames();
        assertThat(names.get(0)).startsWith("Recall");
    }

    @Test
    @DisplayName("同一份输入跑两次，所有实验的指标数字必须一模一样")
    void all_scores_should_be_identical_across_runs() {
        DiagnosisReport first = runOnce();
        DiagnosisReport second = runOnce();

        assertThat(second.metricNames()).isEqualTo(first.metricNames());
        assertThat(second.results().keySet()).isEqualTo(first.results().keySet());

        for (ExperimentId id : first.results().keySet()) {
            ExperimentResult a = first.results().get(id);
            ExperimentResult b = second.results().get(id);
            for (String metric : first.metricNames()) {
                assertThat(b.score(metric))
                        .as("%s 的 %s 应该可复现", id, metric)
                        .isEqualTo(a.score(metric));
            }
        }
    }

    @Test
    @DisplayName("结论也要可复现——同样的数字应推出同样的说法")
    void findings_should_be_identical_across_runs() {
        DiagnosisReport first = runOnce();
        DiagnosisReport second = runOnce();

        assertThat(second.findings()).isEqualTo(first.findings());
    }

    @Test
    @DisplayName("两个切分方案 × 三种召回，指标列不该被召回方式的数量带乱")
    void column_count_should_not_depend_on_experiment_count() {
        DiagnosisReport report = runOnce();

        // 6 次实验共用同一组指标。列数应该是 3，不是 6 × 3。
        assertThat(report.results()).hasSize(6);
        assertThat(report.metricNames()).hasSize(3);
    }

    /**
     * 跑一次诊断。
     *
     * <p>用不依赖任何外部资源的夹具：召回器是假的、语料是内存里的。
     * 所有召回方式都返回空列表——这里只关心"列顺序和数字稳不稳"，
     * 不关心数字好不好看。
     */
    private static DiagnosisReport runOnce() {
        DiagnosisFixtures.Fixture fixture = DiagnosisFixtures.fixture(4);

        // 两个切分方案，这样报告里有多行；列顺序的问题也更容易暴露
        List<ExperimentPlan> plans = new ArrayList<>();
        plans.add(DiagnosisFixtures.plan("p1", fixture.chunks()));
        plans.add(DiagnosisFixtures.variantPlan("p2", fixture.chunks(), 100));

        // 三种召回方式都给一个假召回器（返回空），否则工厂会报"没准备"
        Retriever empty = (query, topK) -> List.of();
        RetrieverFactory factory = DiagnosisFixtures.factory(Map.of(
                "vector", empty,
                "keyword", empty,
                "hybrid", empty));

        DiagnosisEngine engine = new DiagnosisEngine(corpus -> factory);
        return engine.diagnose(DiagnosisFixtures.request(fixture, List.copyOf(plans)));
    }
}
