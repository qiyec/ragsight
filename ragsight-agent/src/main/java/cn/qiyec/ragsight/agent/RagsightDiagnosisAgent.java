package cn.qiyec.ragsight.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 会自己决定下一步试什么的诊断 Agent。
 *
 * <p><b>它和第一层的分工</b>
 * <pre>
 *   第一层  DiagnosisEngine  算指标、跑对比、从指标推结论   —— 纯计算，结果可复现
 *   第二层  本类             看着中间结果决定下一步试什么   —— 会调大模型
 * </pre>
 *
 * <p><b>为什么需要它</b>：全组合是跑不完的。6 种切分 × 3 种召回 × 2 种重排
 * = 36 组，语料再大点就更没边。而实际上大部分组合没必要试——
 * 如果召回率已经满分，那问题在排序，调切分纯属浪费。
 *
 * <p>这个 Agent 就是一个一个改、看结果、再决定下一个改什么。通常 3 到 4 轮
 * 就能定位瓶颈，而不是把 36 组跑一遍。
 *
 * <p><b>它不下结论</b>。结论仍然是 {@code DiagnosisEngine} 用固定规则
 * 从指标推出来的（"召回率不够"、"重排没效果"这类），
 * Agent 负责的是"决定试什么"和"把结论串起来讲"。
 * 这样同一份数据不会因为大模型这次发挥不同而给出两种说法。
 *
 * <p><b>用法</b>：Agent 有生命周期，用完要关。推荐在应用启动时把
 * {@code Builder} 配好，每次诊断从它 build 一个新实例：
 * <pre>
 *   RagsightDiagnosisAgent agent = RagsightDiagnosisAgent.builder()
 *           .model("dashscope:qwen-plus")
 *           .build(session, listener);
 *   try (agent) {
 *       String conclusion = agent.diagnose("先把基准跑出来");
 *   }
 * </pre>
 */
public class RagsightDiagnosisAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RagsightDiagnosisAgent.class);

    /** 默认模型。DashScope 上的 qwen-plus，够用且便宜。 */
    public static final String DEFAULT_MODEL = "dashscope:qwen-plus";

    /**
     * 让 Agent 最多做多少次推理-工具循环。
     *
     * <p>设这个上限是因为：一旦模型陷入"反复试同一个东西"的循环，
     * 每次循环都是真金白银的 token 和真的实验开销。给个上限，
     * 到点了它会把已有的结果整理出来，而不是无限试下去。
     *
     * <p>12 次够它跑：1 次基准 + 4 到 5 次单变量对比 + 若干次查看 + 收尾。
     */
    public static final int DEFAULT_MAX_ITERS = 12;

    private final ReActAgent agent;

    private RagsightDiagnosisAgent(ReActAgent agent) {
        this.agent = agent;
    }

    /**
     * 开始诊断。给的这句话是"任务说明"，Agent 会自己决定先调用哪个工具。
     *
     * <p>这是个阻塞调用，会一直等到 Agent 认为该收尾了
     * （或者撞上 {@code maxIters} 上限）。整个过程的每一步都会通过
     * {@link Listener} 报出来。
     */
    public String diagnose(String task) {
        try {
            Msg reply = agent.call(task, RuntimeContext.empty()).block();
            return reply == null ? "（模型没有返回内容）" : reply.getTextContent();
        } catch (RuntimeException e) {
            log.error("诊断 Agent 执行失败", e);
            throw new AgentRunException(
                    "诊断 Agent 执行失败：" + rootMessage(e), e);
        }
    }

    @Override
    public void close() {
        try {
            agent.close();
        } catch (RuntimeException e) {
            // 关闭失败不该盖掉真正的业务异常，记一笔就够了
            log.warn("关闭诊断 Agent 时出错：{}", e.getMessage());
        }
    }

    /** 从异常链里找到最有信息量的那句。 */
    private static String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String msg = current.getMessage();
        return msg == null || msg.isBlank() ? current.getClass().getSimpleName() : msg;
    }

    /** 执行 Agent 时的失败。 */
    public static class AgentRunException extends RuntimeException {
        public AgentRunException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Agent 的配置。
     *
     * <p>和一次具体的诊断分开：模型名、提示词这些是应用启动时定好的，
     * 不属于某次诊断请求。每次诊断用 {@link #build} 建一个新实例。
     */
    public static final class Builder {

        private String model = DEFAULT_MODEL;
        private int maxIters = DEFAULT_MAX_ITERS;
        private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        private List<ProgressListener> progressListeners = new ArrayList<>();

        private Builder() {
        }

        /**
         * 用哪个模型。格式是 {@code "厂商:模型名"}，比如
         * {@code "dashscope:qwen-plus"}、{@code "openai:gpt-4o"}。
         *
         * <p>凭据从环境变量读（DashScope 是 {@code DASHSCOPE_API_KEY}），
         * 不在这里传，避免 Key 出现在配置对象里被日志打出来。
         */
        public Builder model(String model) {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model 不能为空");
            }
            this.model = model;
            return this;
        }

        /** 最多做多少次推理-工具循环。 */
        public Builder maxIters(int maxIters) {
            if (maxIters <= 0) {
                throw new IllegalArgumentException("maxIters 必须为正数，实际为 " + maxIters);
            }
            this.maxIters = maxIters;
            return this;
        }

        /** 换一套系统提示词。默认那套已经说清了工作方式。 */
        public Builder systemPrompt(String systemPrompt) {
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalArgumentException("systemPrompt 不能为空");
            }
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** 加一个进度监听，用来把过程显示到界面上。 */
        public Builder progressListener(ProgressListener listener) {
            if (listener != null) {
                this.progressListeners.add(listener);
            }
            return this;
        }

        /**
         * 为一次诊断建一个 Agent 实例。
         *
         * @param session 这次诊断要用的"笔记本"，里面有语料和已跑过的结果
         */
        public RagsightDiagnosisAgent build(DiagnosisSession session) {
            if (session == null) {
                throw new IllegalArgumentException("session 不能为空，它保存着这次诊断的语料和进度");
            }

            Toolkit toolkit = buildToolkit(session);

            log.info("创建诊断 Agent：模型={}，语料={}，最多 {} 轮",
                    model, session.corpusLabel(), maxIters);

            ReActAgent.Builder builder = ReActAgent.builder()
                    .name("ragsight-diagnoser")
                    .sysPrompt(systemPrompt)
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(maxIters);

            // 有监听者才挂进度上报。没人听的时候少一层间接调用。
            if (!progressListeners.isEmpty()) {
                builder.hook(new ReportingHook(new CompositeListener(progressListeners)));
            }

            return new RagsightDiagnosisAgent(builder.build());
        }
    }

    /**
     * 造工具集。
     *
     * <p>抽成静态方法是为了能被测试直接调用——**"工具到底有没有
     * 注册成模型能看见的 schema"这件事必须验证**。
     * 如果 {@code @Tool} 反射注册有问题，模型只会聊天不干活，
     * 而且不会有任何报错，非常难查。
     */
    static Toolkit buildToolkit(DiagnosisSession session) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DiagnosisTools(session));
        return toolkit;
    }

    /**
     * 把多个监听者合成一个，让 Hook 只挂一次。
     *
     * <p>上报失败绝不能影响诊断本身——用户多挂一个会抛异常的监听，
     * 不该让整次诊断失败。所以这里逐个包一层 try。
     */
    private static final class CompositeListener implements ProgressListener {

        private final List<ProgressListener> listeners;

        CompositeListener(List<ProgressListener> listeners) {
            this.listeners = List.copyOf(listeners);
        }

        @Override
        public void onStep(String message) {
            each(l -> l.onStep(message));
        }

        @Override
        public void onToolCall(String toolName, String arguments) {
            each(l -> l.onToolCall(toolName, arguments));
        }

        @Override
        public void onToolResult(String toolName, String result) {
            each(l -> l.onToolResult(toolName, result));
        }

        @Override
        public void onError(String message) {
            each(l -> l.onError(message));
        }

        private void each(java.util.function.Consumer<ProgressListener> action) {
            for (ProgressListener listener : listeners) {
                try {
                    action.accept(listener);
                } catch (RuntimeException e) {
                    log.warn("进度监听器抛异常（不影响诊断）：{}", e.getMessage());
                }
            }
        }
    }

    /**
     * 默认的系统提示词。
     *
     * <p>写这么细是因为：不交代清楚工作方式的话，模型很容易
     * 一次性要求跑一堆组合——那就退化成穷举了，第二层就没有意义了。
     *
     * <p>另外要明确告诉它"别自己编结论"。结论由工具背后的诊断引擎
     * 用固定规则算，模型只负责选择试什么和把结果讲清楚。
     */
    static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个 RAG 检索效果的诊断助手。你的工作方式是**一次只改一个东西**，
            看结果，再决定下一个改什么。不要试图一次跑完所有组合。

            你手上有这些工具：
              run_baseline          跑基准（第一步必须先做）
              compare_one_change    把当前最好的配置只改一处，跟改之前对比
              list_attempts         看已经跑过什么
              finish                收尾给结论

            工作流程：

            1. 先调用 run_baseline，拿到一组基准数据。它会告诉你下一步建议往哪试。
            2. 然后反复调用 compare_one_change，每次**只改一个东西**。
               可选的方向有四个：chunk_size（切分窗口）、strategy（切分策略）、
               retriever（召回方式）、rerank（要不要重排）。
            3. 判断标准看 MRR（排序质量），同时留意 Recall（召回率）。
               要记住 Recall 是上限：召回率不够的时候，先把召回提上去，
               排序再怎么调也救不回来。
            4. 满足下面任一条件就调用 finish 收尾：
               - 已经找到明显更好的配置（MRR 提升 0.05 以上）
               - 试了 3 到 4 个方向都没什么效果
               - 主要方向都试过了

            几条必须守住的规矩：

            - **一次只改一个参数。** 同时改两个的话，指标变了也不知道是谁的功劳，
              这个对比就白做了。
            - **不要自己编数字。** 所有指标都以工具返回的为准。
            - **不要自己下结论。** "瓶颈在召回还是在排序"这类判断，
              工具返回的文字里已经写好了（那是诊断引擎用固定规则推出来的）。
              你负责的是选择试什么、以及最后把整件事讲清楚。
            - **不要重复试已经试过的东西。** 工具会告诉你还剩哪些方向。
            - 如果某个改动明显让指标变差了，别反复试它，换个方向。

            最后 finish 之后，用中文简要总结这次的发现，说清：
            推荐哪个配置、为什么、相对于起点提升在哪。
            """;

    // ------------------------------------------------------------------
    // 进度上报
    // ------------------------------------------------------------------

    /**
     * 诊断过程的进度监听。
     *
     * <p>让界面能显示"Agent 正在干什么"。调 Agent 最难受的地方就是
     * 它闷头想半天没动静，不知道是卡住了还是在正常工作。
     */
    public interface ProgressListener {

        /** 开始一步。 */
        void onStep(String message);

        /** 工具调用。 */
        void onToolCall(String toolName, String arguments);

        /** 工具返回。 */
        void onToolResult(String toolName, String result);

        /** 出错。 */
        void onError(String message);
    }
}
