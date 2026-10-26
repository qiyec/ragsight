package cn.qiyec.ragsight.agent;

import cn.qiyec.ragsight.diagnosis.CorpusCatalog;
import cn.qiyec.ragsight.diagnosis.DiagnosisService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 循环的集成测试，用假模型驱动。
 *
 * <p>这里验证的是**管道通不通**：模型说要调工具 → Agent 真的去调 →
 * 结果被送回模型 → 模型给最终答复 → 循环结束。
 *
 * <p>为什么这件事值得单独测：它是最典型的"静默失效"。
 * 工具注册错了、循环没接上、结果没回传，这些都不会报错，
 * 表现只是"Agent 聊了几句就结束了，什么都没干"。
 * 用真模型手工跑一次也能发现，但那样每次都要花钱、花时间，
 * 而且没人会为了改一行代码就跑一次。
 *
 * <p><b>这里不验证</b>模型会不会按"一次只改一个"来做决策——
 * 那需要真模型，见 PROGRESS.md 里记的待验证项。
 */
@DisplayName("Agent 循环（假模型驱动）")
class AgentLoopTest {

    private DiagnosisSession session;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        CorpusCatalog catalog = new CorpusCatalog(
                Path.of("不存在的目录-" + System.nanoTime()).toString());
        session = new DiagnosisSession(
                CorpusCatalog.DEMO_ID, "演示语料", 5, 18,
                new DiagnosisService(catalog),
                new DiagnosisService.ApiRequest(
                        CorpusCatalog.DEMO_ID, null, null, 5, false, false, null, null));
        toolkit = RagsightDiagnosisAgent.buildToolkit(session);
    }

    @Test
    @DisplayName("模型要求调工具时，工具应该真的被执行")
    void tool_should_actually_run() {
        ScriptedModel model = new ScriptedModel()
                .thenCallTool("list_attempts")
                .thenSay("我看了一下，还没跑过任何配置。");

        try (ReActAgent agent = model.buildAgent(toolkit, 6)) {
            Msg reply = model.run(agent, "开始诊断");

            assertThat(reply).isNotNull();
            assertThat(reply.getTextContent()).contains("还没跑过");
        }

        // 关键断言：模型被调用了两次（一次要工具、一次给答复），
        // 说明工具那次是走完了一整个来回，不是被跳过
        assertThat(model.callCount()).isEqualTo(2);
        assertThat(model.remainingSteps()).isZero();
    }

    @Test
    @DisplayName("工具结果要被送回模型——否则模型看不到数据，只能瞎猜")
    void tool_result_should_be_sent_back_to_model() {
        ScriptedModel model = new ScriptedModel()
                .thenCallTool("run_baseline", Map.of("chunk_size", 90))
                .thenSay("基准跑完了。");

        try (ReActAgent agent = model.buildAgent(toolkit, 6)) {
            model.run(agent, "先跑基准");
        }

        // 第二次调模型时的对话里，应该能看到 run_baseline 的输出。
        // 那句输出里必然有"基准结果"这几个字（见 DiagnosisTools）。
        assertThat(model.callCount()).isEqualTo(2);
        // 注意用 allConversationText() 而不是 List.toString()——
        // Msg 没有重写 toString，直接打出来是对象身份（Msg@1a2b3c），
        // 不含任何内容。这个坑第一版就踩了。
        assertThat(model.allConversationText()).contains("基准结果");
    }

    @Test
    @DisplayName("走一遍自适应流程，看总共跑了多少次实验")
    void adaptive_path_should_report_experiment_count() {
        ScriptedModel model = new ScriptedModel()
                .thenCallTool("run_baseline", Map.of("chunk_size", 90))
                .thenCallTool("compare_one_change",
                        Map.of("change", "rerank", "new_value", "true"))
                .thenCallTool("compare_one_change",
                        Map.of("change", "retriever", "new_value", "keyword"))
                .thenCallTool("compare_one_change",
                        Map.of("change", "chunk_size", "new_value", "200"))
                .thenCallTool("finish")
                .thenSay("找到更好的配置了。");

        try (ReActAgent agent = model.buildAgent(toolkit, 10)) {
            model.run(agent, "自适应诊断");
        }

        // 把实际跑过的每一步打出来，看清次数是怎么累起来的
        System.out.println("=== 自适应流程实际跑过的配置 ===");
        session.attempted().forEach(a -> System.out.println("  " + a.displayName()
                + "   Recall=" + String.format("%.3f", a.recall())
                + "  MRR=" + String.format("%.3f", a.mrr())));
        System.out.println("  合计 " + session.experimentCount() + " 次实验");

        // 一轮基准 + 三次单变量对比。
        // 基准是 2 种切分 × 3 种召回 = 6 次；
        // 对比每次跑 2 组（改之前 / 改之后），但"改之前"会落在同一批里，
        // 所以具体次数取决于每次改动落在切分维度还是召回维度。
        //
        // 全穷举这个语料要多少次：3 种切分 × 3 种召回 × 2 种重排 = 18 组。
        // 注意单独看"3 切分 × 3 召回 = 9"是不对的——重排那一维
        // 是每个切分各自加一组，所以总共有 18 组。
        assertThat(session.experimentCount())
                .as("自适应流程应该比全穷举（18 次）少")
                .isLessThan(18);
        assertThat(session.attempted())
                .as("试过的配置数应该远少于全部组合")
                .isNotEmpty();

        // 最后一次改动试的是"递归切分 200 字"，这一组在基准里没有出现过
        // （基准只有 90 字）。它的指标比基准最好的一档还高——
        // 这正是自适应探索的价值：它走到了穷举网格里也有、
        // 但"只跑默认那几档"时不会碰到的地方。
        assertThat(session.best().mrr())
                .as("探索之后应该比基准最好的一档更好")
                .isGreaterThan(0.813);

        System.out.printf(
                "%n[省了多少] 自适应：%d 次实验。%n"
                        + "  全穷举这个语料：3 种切分 × 3 种召回 × 2 种重排 = 18 组。%n"
                        + "  自适应只跑了 %d 次，而且其中一组（递归切分 200 字）%n"
                        + "  是基准里没有的档位——它是顺着中间结果走出来的。%n",
                session.experimentCount(), session.experimentCount());
    }

    @Test
    @DisplayName("连续调多个工具时，每一步的结果都要累积进对话")
    void multiple_tool_calls_should_chain() {
        ScriptedModel model = new ScriptedModel()
                .thenCallTool("run_baseline", Map.of("chunk_size", 90))
                .thenCallTool("compare_one_change",
                        Map.of("change", "rerank", "new_value", "true"))
                .thenCallTool("finish")
                .thenSay("诊断完成。");

        try (ReActAgent agent = model.buildAgent(toolkit, 8)) {
            Msg reply = model.run(agent, "完整跑一遍");
            assertThat(reply.getTextContent()).contains("诊断完成");
        }

        assertThat(model.callCount()).isEqualTo(4);
        assertThat(model.remainingSteps()).isZero();

        // 累积下来的对话里应该同时有基准、对比、收尾三段输出，
        // 这就是"结果一步步回传给模型"的证据
        String all = model.allConversationText();
        assertThat(all).contains("基准结果");
        assertThat(all).contains("只改了一处");
        assertThat(all).contains("诊断结论");
    }

    @Test
    @DisplayName("工具报错时不该把 Agent 弄崩，而是把错误交回模型")
    void tool_error_should_go_back_to_model() {
        // 故意给一个不认识的 change，工具会返回一句提示而不是抛异常
        ScriptedModel model = new ScriptedModel()
                .thenCallTool("compare_one_change",
                        Map.of("change", "乱填的", "new_value", "x"))
                .thenSay("我换个方式试。");

        try (ReActAgent agent = model.buildAgent(toolkit, 6)) {
            Msg reply = model.run(agent, "试一下");

            // Agent 正常结束，没有崩
            assertThat(reply.getTextContent()).contains("换个方式");
        }
        assertThat(model.callCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("没跑基准就直接对比时，工具应给出可读的提示")
    void comparing_without_baseline_should_be_handled() {
        ScriptedModel model = new ScriptedModel()
                .thenCallTool("compare_one_change",
                        Map.of("change", "rerank", "new_value", "true"))
                .thenSay("那我先跑基准。");

        try (ReActAgent agent = model.buildAgent(toolkit, 6)) {
            model.run(agent, "直接对比");
        }

        // 模型第二次看到的对话里，应该带着"先调用 run_baseline"这句提示。
        // 这正是我们要的行为：工具用文字引导模型，而不是抛异常。
        assertThat(model.allConversationText()).contains("run_baseline");
    }

    @Test
    @DisplayName("maxIters 上限要真的生效，模型不能无限调工具")
    void max_iters_should_cap_the_loop() {
        // 剧本里排 12 次要工具，但把上限设成 3
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < 12; i++) {
            model.thenCallTool("list_attempts");
        }
        model.thenSay("终于说完了。");

        try (ReActAgent agent = model.buildAgent(toolkit, 3)) {
            model.run(agent, "一直调工具");
        }

        // 有上限的话不会把 12 步全跑完。
        // 这里断言"调用次数明显少于剧本步数"，
        // 而不是精确等某个数——框架具体怎么计数是它的事。
        assertThat(model.callCount())
                .as("上限应该拦住无限循环")
                .isLessThan(13);
        assertThat(model.remainingSteps())
                .as("剧本应该没用完，说明确实被上限截住了")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("进度 Hook 要被触发，否则界面上什么都看不到")
    void progress_hook_should_fire() {
        ScriptedModel model = new ScriptedModel()
                .thenCallTool("run_baseline", Map.of("chunk_size", 90))
                .thenSay("好了。");

        List<String> steps = new java.util.ArrayList<>();
        List<String> toolCalls = new java.util.ArrayList<>();
        List<String> toolResults = new java.util.ArrayList<>();

        DiagnosisSession hookSession = newSession();
        Toolkit hookToolkit = RagsightDiagnosisAgent.buildToolkit(hookSession);

        RagsightDiagnosisAgent.ProgressListener listener =
                new RagsightDiagnosisAgent.ProgressListener() {
                    @Override
                    public void onStep(String message) {
                        steps.add(message);
                    }

                    @Override
                    public void onToolCall(String toolName, String arguments) {
                        toolCalls.add(toolName);
                    }

                    @Override
                    public void onToolResult(String toolName, String result) {
                        toolResults.add(toolName);
                    }

                    @Override
                    public void onError(String message) {
                        steps.add("ERROR:" + message);
                    }
                };

        ReActAgent agent = ReActAgent.builder()
                .name("hook-test")
                .sysPrompt("测试")
                .model(model)
                .toolkit(hookToolkit)
                .maxIters(6)
                .hook(newHook(listener))
                .build();

        try (agent) {
            model.run(agent, "跑一次");
        }

        // 这三样都要有，界面上才有东西可显示
        assertThat(toolCalls).as("要能看到调了哪个工具").contains("run_baseline");
        assertThat(toolResults).as("要能看到工具返回了").contains("run_baseline");
        assertThat(steps).as("要能看到模型的思考").isNotEmpty();
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static DiagnosisSession newSession() {
        CorpusCatalog catalog = new CorpusCatalog(
                Path.of("不存在的目录-" + System.nanoTime()).toString());
        return new DiagnosisSession(
                CorpusCatalog.DEMO_ID, "演示语料", 5, 18,
                new DiagnosisService(catalog),
                new DiagnosisService.ApiRequest(
                        CorpusCatalog.DEMO_ID, null, null, 5, false, false, null, null));
    }

    /** AgentTraceHook 是包内可见的，测试同包所以能直接用。 */
    private static io.agentscope.core.hook.Hook newHook(
            RagsightDiagnosisAgent.ProgressListener listener) {
        return new AgentTraceHook(listener, "#test");
    }
}
