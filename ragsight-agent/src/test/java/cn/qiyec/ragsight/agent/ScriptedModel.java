package cn.qiyec.ragsight.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个按剧本走的假模型。
 *
 * <p><b>为什么需要它</b>：真实的诊断 Agent 要调大模型，而大模型
 * 需要 Key、要联网、结果还不确定。那样"Agent 的循环对不对"这件事
 * 就没法自动测——只能靠手工跑一次看看。
 *
 * <p>但循环本身是确定性的：模型说"调用这个工具"，
 * Agent 就该去调、把结果送回模型、再问下一步。
 * 把模型换成一个"照着剧本回答"的假货，这段流程就能自动验证了。
 *
 * <p>它能验证的东西（都是真实的集成风险）：
 * <ul>
 *   <li>工具注册对不对——注册错了假模型点名要调时就会失败</li>
 *   <li>工具真的被执行了，返回值真的被送回模型</li>
 *   <li>循环会一直转到模型给出纯文本为止</li>
 *   <li>{@code maxIters} 上限真的生效</li>
 *   <li>进度 Hook 真的被触发了</li>
 * </ul>
 *
 * <p>它<b>不</b>验证的：模型会不会按"一次只改一个"来做决策。
 * 那是模型的事，得用真 Key 跑一次才知道。这里测的是管道，不是智商。
 */
final class ScriptedModel implements Model {

    private final Deque<Step> script = new ArrayDeque<>();
    private final List<List<Msg>> receivedConversations = new ArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();

    /** 剧本的一步：要么要调工具，要么给最终答复。 */
    private record Step(String toolName, Map<String, Object> input, String finalText) {

        static Step callTool(String name, Map<String, Object> input) {
            return new Step(name, input, null);
        }

        static Step say(String text) {
            return new Step(null, Map.of(), text);
        }
    }

    /** 下一步要调这个工具。 */
    ScriptedModel thenCallTool(String toolName) {
        return thenCallTool(toolName, Map.of());
    }

    ScriptedModel thenCallTool(String toolName, Map<String, Object> input) {
        script.add(Step.callTool(toolName, input));
        return this;
    }

    /** 下一步给最终答复（循环到此结束）。 */
    ScriptedModel thenSay(String text) {
        script.add(Step.say(text));
        return this;
    }

    /** 剧本里排了但没用完的步数。用来确认循环没有多跑或少跑。 */
    int remainingSteps() {
        return script.size();
    }

    /** 模型一共被调用了几次。 */
    int callCount() {
        return callCount.get();
    }

    /** 第几次调用时收到的对话内容。可以据此断言工具结果被送回来了。 */
    List<Msg> conversationAt(int index) {
        return receivedConversations.get(index);
    }

    /**
     * 全部对话内容，拼成一段文字方便断言。
     *
     * <p>为什么要绕这么一圈：{@code Msg}、{@code ToolUseBlock}、
     * {@code ToolResultBlock} 都没有重写 {@code toString()}，
     * 直接打出来是对象身份（如 {@code ToolResultBlock@65ae095c}），
     * 不含任何内容。所以必须一层层拆开取。
     *
     * <p>拆的层次：
     * <ul>
     *   <li>{@code Msg.getTextContent()} —— 模型自己说的文字</li>
     *   <li>{@code Msg.getContent()} 里的 {@code TextBlock} —— 同样是文字</li>
     *   <li>{@code ToolResultBlock.getOutput()} 里的块 —— 工具返回的内容</li>
     * </ul>
     *
     * <p>前两版就是没拆到底，断言全部落空。那两次都是测试写法的问题，
     * 不是"工具结果没回传给模型"——从上一次的输出能看到
     * {@code ToolResultBlock@65ae095c} 确实出现在对话里了。
     */
    String allConversationText() {
        StringBuilder sb = new StringBuilder();
        for (List<Msg> conversation : receivedConversations) {
            for (Msg msg : conversation) {
                appendMsg(sb, msg);
            }
        }
        return sb.toString();
    }

    private static void appendMsg(StringBuilder sb, Msg msg) {
        if (msg.getTextContent() != null) {
            sb.append(msg.getTextContent()).append('\n');
        }
        for (ContentBlock block : msg.getContent()) {
            appendBlock(sb, block);
        }
    }

    /** 工具返回的内容藏在 ToolResultBlock.getOutput() 里，不拆开就看不到。 */
    private static void appendBlock(StringBuilder sb, ContentBlock block) {
        if (block instanceof TextBlock text) {
            sb.append(text.getText()).append('\n');
        } else if (block instanceof ToolResultBlock result) {
            if (result.getOutput() != null) {
                for (ContentBlock inner : result.getOutput()) {
                    appendBlock(sb, inner);
                }
            }
        } else if (block instanceof ToolUseBlock use) {
            // 模型要求调工具的那一条，附上名字方便断言
            sb.append("tool:").append(use.getName()).append('\n');
        }
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages,
                                     List<ToolSchema> tools,
                                     GenerateOptions options) {
        receivedConversations.add(List.copyOf(messages));
        callCount.incrementAndGet();

        Step step = script.poll();
        if (step == null) {
            // 剧本用完了还在被调用，说明循环没有按预期停止。
            // 返回一句文本让它结束，同时这个事实会被测试断言到。
            return Flux.just(textResponse("（剧本已用完）"));
        }

        if (step.toolName() != null) {
            return Flux.just(toolCallResponse(step.toolName(), step.input()));
        }
        return Flux.just(textResponse(step.finalText()));
    }

    @Override
    public String getModelName() {
        return "scripted-test-model";
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .id("resp-text")
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason("stop")
                .build();
    }

    private static ChatResponse toolCallResponse(String toolName,
                                                 Map<String, Object> input) {
        Map<String, Object> args = new LinkedHashMap<>(input);
        ToolUseBlock block = ToolUseBlock.builder()
                .id("call-" + toolName + "-" + System.nanoTime())
                .name(toolName)
                .input(args)
                // content 必须填：它是模型返回的原始参数 JSON。
                // 真实厂商的 formatter 会填这个字段，框架做参数校验时
                // 也读它。不填的话报的是
                //   argument "content" is null
                // 而不是"某个参数缺了"——这个错误信息会把人往错的方向带。
                .content(toJson(args))
                .build();
        List<ContentBlock> content = List.of(block);
        return ChatResponse.builder()
                .id("resp-tool")
                .content(content)
                .finishReason("tool_calls")
                .build();
    }

    /** 把参数拼成 JSON。假模型不需要完整实现，够用就行。 */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(v).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /**
     * 用这个假模型建一个 Agent。
     *
     * <p>绕开 {@code RagsightDiagnosisAgent}，直接用 AgentScope 的
     * {@code ReActAgent}——因为这里要测的是"框架的循环 + 我们的工具"
     * 能不能接上，不是我们自己那层封装的 API。
     */
    ReActAgent buildAgent(Toolkit toolkit, int maxIters) {
        return ReActAgent.builder()
                .name("scripted-test-agent")
                .sysPrompt("测试用")
                .model(this)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .build();
    }

    /** 跑一次对话，返回模型的最后回复。 */
    Msg run(ReActAgent agent, String task) {
        return agent.call(task, RuntimeContext.empty()).block();
    }
}
