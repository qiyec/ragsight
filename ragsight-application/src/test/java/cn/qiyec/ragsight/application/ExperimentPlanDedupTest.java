package cn.qiyec.ragsight.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验方案去重的测试。
 *
 * <p>要防的是这个场景：用户用 {@code --chunk-size 90} 指定自定义切分，
 * 而内置基线里恰好也有一个 90 字的方案。两个方案 id 不同
 *（{@code custom-90} / {@code recursive-90}）但**实际切得一模一样**。
 *
 * <p>不去重的后果：
 * <ul>
 *   <li>报告里出现两行同名方案，读者以为工具出错了</li>
 *   <li>多跑一组实验（白花时间，接真实模型时白花钱）</li>
 *   <li>热力图多一行重复</li>
 * </ul>
 *
 * <p>判断是否重复用 {@code describe()} 而不是 id——id 是人起的名字，
 * {@code describe()} 才是"实际怎么切"。
 */
@DisplayName("实验方案去重")
class ExperimentPlanDedupTest {

    @Test
    @DisplayName("自定义方案和基线里的实际一样时，只保留自定义的那个")
    void should_drop_baseline_that_matches_preferred() {
        // 造一个和 recursiveDefault 实际相同的方案，只是 id 不同
        ExperimentPlan custom = new ExperimentPlan("custom-90", "递归切分 90 字",
                new cn.qiyec.ragsight.domain.knowledge.chunking
                        .RecursiveCharacterChunkingStrategy(90, 18), 5);

        List<ExperimentPlan> plans = ExperimentPlan.concatWithoutDuplicates(
                custom, ExperimentPlan.chunkingBaselines(5));

        // 内置基线有 3 个：递归 90、递归 200、固定 90。
        // 递归 90 和自定义的实际一样要被剔掉，所以是
        // 自定义 90 + 递归 200 + 固定 90 = 3 个
        assertThat(plans)
                .as("90 字那个不该出现两次，但其他两个基线要留着")
                .hasSize(3);

        assertThat(plans)
                .extracting(ExperimentPlan::id)
                .as("第一个是我们指定的那个")
                .startsWith("custom-90");

        assertThat(plans)
                .extracting(ExperimentPlan::id)
                .as("内置的 recursive-90 被剔掉了")
                .doesNotContain("recursive-90");
    }

    @Test
    @DisplayName("参数不同就不该被剔掉")
    void should_keep_baselines_that_differ() {
        ExperimentPlan custom = new ExperimentPlan("custom-120", "递归切分 120 字",
                new cn.qiyec.ragsight.domain.knowledge.chunking
                        .RecursiveCharacterChunkingStrategy(120, 24), 5);

        List<ExperimentPlan> plans = ExperimentPlan.concatWithoutDuplicates(
                custom, ExperimentPlan.chunkingBaselines(5));

        // 自定义 120 + 内置的 90 + 内置的 200 + 内置的固定 90 = 4 个
        assertThat(plans).hasSize(4);
        assertThat(plans).extracting(ExperimentPlan::id)
                .containsExactly("custom-120", "recursive-90", "recursive-200", "fixed-90");
    }

    @Test
    @DisplayName("切分策略不同也算不同——哪怕窗口大小一样")
    void different_strategy_with_same_size_is_not_duplicate() {
        // 固定窗口 90 和递归切分 90：窗口一样但切法不同，不能算重复
        ExperimentPlan custom = new ExperimentPlan("custom-fixed-90", "固定窗口 90 字",
                new cn.qiyec.ragsight.domain.knowledge.chunking
                        .FixedWindowChunkingStrategy(90, 18), 5);

        List<ExperimentPlan> plans = ExperimentPlan.concatWithoutDuplicates(
                custom, ExperimentPlan.chunkingBaselines(5));

        // 三个都该留着：自定义固定90 + 内置递归90 + 内置递归200
        //（注意内置基线里那个"固定窗口 90 字"这次也应该被剔掉——
        //  它和自定义方案实际一样）
        assertThat(plans).extracting(ExperimentPlan::id)
                .contains("recursive-90", "recursive-200")
                .doesNotContain("fixed-90");
    }

    @Test
    @DisplayName("没有重复时一个都不该丢")
    void should_keep_everything_when_no_duplicate() {
        ExperimentPlan preferred = new ExperimentPlan("p", "某个完全不同的方案",
                new cn.qiyec.ragsight.domain.knowledge.chunking
                        .RecursiveCharacterChunkingStrategy(1000, 100), 5);

        List<ExperimentPlan> plans = ExperimentPlan.concatWithoutDuplicates(
                preferred, ExperimentPlan.chunkingBaselines(5));

        // 1 个自定义 + 3 个内置，一个都没少
        assertThat(plans).hasSize(4);
    }

    @Test
    @DisplayName("结果顺序要稳定：指定的排第一，其余保持原来的顺序")
    void order_should_be_stable() {
        ExperimentPlan custom = new ExperimentPlan("custom-120", "递归切分 120 字",
                new cn.qiyec.ragsight.domain.knowledge.chunking
                        .RecursiveCharacterChunkingStrategy(120, 24), 5);

        List<ExperimentPlan> first = ExperimentPlan.concatWithoutDuplicates(
                custom, ExperimentPlan.chunkingBaselines(5));
        List<ExperimentPlan> second = ExperimentPlan.concatWithoutDuplicates(
                custom, ExperimentPlan.chunkingBaselines(5));

        assertThat(second).extracting(ExperimentPlan::id)
                .as("两次调用顺序必须一样，否则报告的基准会变")
                .isEqualTo(first.stream().map(ExperimentPlan::id).toList());
        assertThat(first.get(0).id()).isEqualTo("custom-120");
    }
}
