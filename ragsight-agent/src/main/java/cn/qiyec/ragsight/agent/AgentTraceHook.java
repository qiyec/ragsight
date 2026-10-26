package cn.qiyec.ragsight.agent;

import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录 Agent 的完整执行轨迹：发了什么、回了什么、调了什么、花了多少。
 *
 * <p>有两个出口：
 * <ul>
 *   <li><b>日志</b>（DEBUG/INFO）——完整的，给排查和学习用</li>
 *   <li><b>进度监听器</b>——简略的，给网页实时显示用</li>
 * </ul>
 *
 * <p>为什么合在一个类里而不是挂两个 Hook：一个观察点只有一个理解，
 * 两个 Hook 各自解析同一批事件，一旦框架的事件顺序变了，
 * 它们的输出就会不一致。而且日志和界面显示的应该是同一件事。
 *
 * <p><b>它不改执行结果。</b> 所有回调都把原事件原样返回。
 * 在 Hook 里修改事件会让调试变难——行为不对时你分不清是 Agent 的问题
 * 还是 Hook 改的。
 *
 * <h2>日志长什么样</h2>
 * <pre>
 * ══════ 诊断开始 ══════
 * 请求 id : a3
 * 语料    : 示例：corpus
 * 模型    : dashscope:qwen-plus
 * 最大轮数: 12
 * 系统提示词（1152 字符）:
 *   ...
 *
 * ───── 第 1 轮：请求 ─────
 * 发给模型 3 条消息，共 1288 字符
 *   [SYSTEM] 1152 字符
 *   ...
 *   [USER] 28 字符
 *   ...
 * 模型参数: temperature=null max_tokens=null stream=true
 *
 * ───── 第 1 轮：响应 ─────
 * 耗时 1332ms | finish_reason=tool_calls
 * token: 输入 1204 | 输出 87 | 合计 1291
 * 文本: 我先跑一下基准看看现状。
 * 工具调用: run_baseline({"chunk_size":120})
 *
 * ───── 工具 run_baseline ─────
 * 耗时 554ms | 返回 12 行
 * 基准结果（语料：示例：corpus，召回条数 5）：
 * ...
 * </pre>
 *
 * <p>日志量不小（一次诊断约 200 行），因为这是给"想看清 Agent 到底在干什么"
 * 的人准备的。嫌吵就把 {@code cn.qiyec.ragsight.agent.AgentTraceHook}
 * 的级别调到 INFO：那样只剩骨架，没有正文。
 */
final class AgentTraceHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceHook.class);

    private static final String BIG = "════════════════════════════════════════════════════════════";
    private static final String MID = "────────────────────────────────────────────────────────────";

    /** 生成参数一个都没显式设置时的说明。 */
    private static final String NOT_SET = "（没显式设置，全部用模型默认值）";

    /** 报给界面时，工具返回只留这么长（界面上不需要几万字的表格）。 */
    private static final int PREVIEW_LIMIT = 900;

    /** 界面用的监听器。命令行模式下没有，可以是 null。 */
    private final RagsightDiagnosisAgent.ProgressListener listener;

    /** 一次诊断的短标识。并发时用它把日志串起来。 */
    private final String requestId;

    private int round;
    /** -1 表示还没收到请求事件。避免算出天文数字的耗时。 */
    private long reasoningStartNanos = -1;
    private final Map<String, Long> toolStartNanos = new LinkedHashMap<>();

    private int totalInputTokens;
    private int totalOutputTokens;
    private int toolCallCount;

    AgentTraceHook(RagsightDiagnosisAgent.ProgressListener listener, String requestId) {
        this.listener = listener;
        this.requestId = requestId;
    }

    // ------------------------------------------------------------------
    // 由 RagsightDiagnosisAgent 在开始/结束时调用
    // ------------------------------------------------------------------

    /** 开始。把"这次跑的是什么"先记下来，后面每条日志都能对上号。 */
    void begin(String corpusLabel, String modelName, int maxIters, String systemPrompt) {
        log.debug("\n{}\n诊断开始 [{}]\n  语料    : {}\n  模型    : {}\n  最大轮数: {}\n{}",
                BIG, requestId, corpusLabel, modelName, maxIters, BIG);
        log.debug("\n系统提示词（{} 字符，每轮都会发给模型）:\n{}\n{}",
                systemPrompt == null ? 0 : systemPrompt.length(),
                systemPrompt, MID);
    }

    /** 结束。把整趟下来的总量记一下——这是看"贵不贵、慢不慢"的地方。 */
    void finish(String lastReply) {
        log.debug("\n{}\n诊断结束 [{}]\n  轮数    : {}\n  工具调用: {} 次\n"
                        + "  token   : 输入 {} | 输出 {} | 合计 {}\n{}\n最终回复（{} 字符）:\n{}\n{}",
                BIG, requestId, round, toolCallCount,
                totalInputTokens, totalOutputTokens, totalInputTokens + totalOutputTokens,
                MID,
                lastReply == null ? 0 : lastReply.length(),
                lastReply == null ? "（无）" : lastReply,
                BIG);

        // 这几条用 INFO：不想看 DEBUG 的人至少知道"跑完了、花了多少"
        log.info("诊断完成 [{}]：{} 轮，{} 次工具调用，{}+{} token",
                requestId, round, toolCallCount, totalInputTokens, totalOutputTokens);
    }

    // ------------------------------------------------------------------
    // Hook 回调
    // ------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            dispatch(event);
        } catch (RuntimeException e) {
            // 记日志/上报出问题，绝不能影响诊断本身
            log.warn("记录执行轨迹时出错（不影响诊断）：{}", e.getMessage());
        }
        return Mono.just(event);
    }

    private <T extends HookEvent> void dispatch(T event) {
        if (event instanceof PreReasoningEvent pre) {
            onPreReasoning(pre);
        } else if (event instanceof PostReasoningEvent post) {
            onPostReasoning(post);
        } else if (event instanceof PreActingEvent pre) {
            onPreActing(pre);
        } else if (event instanceof PostActingEvent post) {
            onPostActing(post);
        } else if (event instanceof ErrorEvent error) {
            Throwable cause = error.getError();
            log.warn("Agent 内部报错 [{}]：{}", requestId,
                    cause == null ? "未知" : cause.getMessage());
            if (listener != null) {
                listener.onError(cause == null ? "未知错误" : String.valueOf(cause.getMessage()));
            }
        }
    }

    /** 模型被调用了。把它收到的全部东西记下来。 */
    private void onPreReasoning(PreReasoningEvent event) {
        round++;
        reasoningStartNanos = System.nanoTime();

        List<Msg> input = event.getInputMessages();

        // 完整消息只在第一轮打：后面每轮都会重发全部历史，
        // 重复打一遍只会让日志翻好几倍，而且信息量为零。
        if (round == 1) {
            log.debug("\n{}\n第 {} 轮：请求 [{}]\n"
                            + "对话消息 {} 条（系统提示词不在里面，它在上面单独打过）\n{}",
                    MID, round, requestId, input == null ? 0 : input.size(), MID);
            logMessages(input);
            log.debug("模型参数: {}", describeOptions(event.getEffectiveGenerateOptions()));
        } else {
            log.debug("\n{}\n第 {} 轮：请求 [{}] | 上下文累计 {} 条消息（新增的在上一轮工具结果里）\n{}",
                    MID, round, requestId, input == null ? 0 : input.size(), MID);
        }

        if (listener != null) {
            listener.onStep("第 " + round + " 轮");
        }
    }

    /** 模型回复了。记下耗时、token、文本和工具调用。 */
    private void onPostReasoning(PostReasoningEvent event) {
        long elapsedMs = reasoningStartNanos < 0
                ? -1
                : (System.nanoTime() - reasoningStartNanos) / 1_000_000;
        Msg reply = event.getReasoningMessage();

        ChatUsage usage = reply == null ? null : reply.getChatUsage();
        if (usage != null) {
            totalInputTokens += usage.getInputTokens();
            totalOutputTokens += usage.getOutputTokens();
        }

        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(MID).append('\n');
        sb.append(String.format("第 %d 轮：响应 [%s]%n", round, requestId));
        sb.append(elapsedMs < 0
                ? "耗时 ？（没收到请求事件，算不出来）\n"
                : String.format("耗时 %d ms%n", elapsedMs));
        if (usage != null) {
            sb.append(String.format("token: 输入 %d | 输出 %d | 合计 %d%n",
                    usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens()));
        }

        if (reply != null) {
            // 模型说的话（含"我打算先跑基准"这类推理文字）
            String text = reply.getTextContent();
            if (text != null && !text.isBlank()) {
                sb.append("文本:\n").append(indent(text.strip())).append('\n');
                if (listener != null) {
                    listener.onStep(text.strip());
                }
            }

            // 它要调的工具
            List<ToolUseBlock> toolUses = reply.getContentBlocks(ToolUseBlock.class);
            if (!toolUses.isEmpty()) {
                sb.append("工具调用:\n");
                for (ToolUseBlock tool : toolUses) {
                    sb.append("  ").append(tool.getName())
                            .append('(').append(rawArgs(tool)).append(")\n");
                }
            }

            // 一条内容都没有，通常意味着出问题了，记一笔
            if ((text == null || text.isBlank()) && toolUses.isEmpty()) {
                sb.append("（本轮没有任何文本，也没有工具调用）\n");
            }
        }

        sb.append(MID);
        log.debug(sb.toString());
    }

    /** 要调工具了。 */
    private void onPreActing(PreActingEvent event) {
        ToolUseBlock tool = event.getToolUse();
        if (tool == null) {
            return;
        }
        toolCallCount++;
        toolStartNanos.put(tool.getName(), System.nanoTime());

        log.debug("\n工具调用 [{}] {}({})", requestId, tool.getName(), rawArgs(tool));

        if (listener != null) {
            listener.onToolCall(tool.getName(), describeInput(tool));
        }
    }

    /** 工具回来了。完整结果进日志，摘要进界面。 */
    private void onPostActing(PostActingEvent event) {
        ToolResultBlock result = event.getToolResult();
        if (result == null) {
            return;
        }

        String full = textOf(result);
        Long startNanos = toolStartNanos.remove(result.getName());
        long elapsedMs = startNanos == null ? -1 : (System.nanoTime() - startNanos) / 1_000_000;

        int lines = full.isEmpty() ? 0 : full.split("\n").length;
        log.debug("\n工具返回 [{}] {} | {} ms | {} 行\n{}\n{}",
                requestId, result.getName(),
                elapsedMs < 0 ? "?" : String.valueOf(elapsedMs),
                lines, full, MID);

        if (listener != null) {
            listener.onToolResult(result.getName(), preview(full));
        }
    }

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    /** 把发给模型的消息逐条打出来。 */
    private static void logMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            log.debug("  （没有消息）");
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            String text = msg.getTextContent();
            String role = msg.getRole() == null ? "?" : msg.getRole().name();
            log.debug("  [{}] {} 字符\n{}", role,
                    text == null ? 0 : text.length(),
                    text == null || text.isBlank() ? "  （空）" : indent(text));
        }
    }

    /**
     * 模型参数。
     *
     * <p>只打非空的：大部分字段是 null（用模型默认值），
     * 全打出来只会让人以为"这些都被设成 null 了"。
     */
    private static String describeOptions(GenerateOptions options) {
        if (options == null) {
            return NOT_SET;
        }
        Map<String, Object> set = new LinkedHashMap<>();
        putIfSet(set, "model", options.getModelName());
        putIfSet(set, "temperature", options.getTemperature());
        putIfSet(set, "top_p", options.getTopP());
        putIfSet(set, "max_tokens", options.getMaxTokens());
        putIfSet(set, "stream", options.getStream());
        putIfSet(set, "thinking_budget", options.getThinkingBudget());
        putIfSet(set, "parallel_tool_calls", options.getParallelToolCalls());
        putIfSet(set, "seed", options.getSeed());

        if (set.isEmpty()) {
            return NOT_SET;
        }
        StringBuilder sb = new StringBuilder();
        set.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private static void putIfSet(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /** 模型返回的原始参数 JSON，比 Map 的 toString 更接近真实情况。 */
    private static String rawArgs(ToolUseBlock tool) {
        String content = tool.getContent();
        if (content != null && !content.isBlank()) {
            return content;
        }
        Map<String, Object> input = tool.getInput();
        return input == null ? "{}" : input.toString();
    }

    /** 给界面用的一行参数说明。 */
    private static String describeInput(ToolUseBlock tool) {
        Map<String, Object> input = tool.getInput();
        if (input == null || input.isEmpty()) {
            return "";
        }
        if (input.size() == 1) {
            Map.Entry<String, Object> only = input.entrySet().iterator().next();
            return only.getKey() + "=" + only.getValue();
        }
        StringBuilder sb = new StringBuilder();
        input.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    /** 工具返回的完整文本。 */
    private static String textOf(ToolResultBlock result) {
        List<ContentBlock> output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return "（空）";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock text) {
                sb.append(text.getText());
            }
        }
        String text = sb.toString();
        if (text.isBlank()) {
            // 不是文本块（比如结构化结果），退回 toString，能看个大概
            text = output.toString();
        }
        return decodeIfJsonEncoded(text);
    }

    /**
     * 工具返回的文本被框架编码成了 JSON 字符串，这里解回来。
     *
     * <p><b>这是个坑</b>：{@code TextBlock.getText()} 拿到的不是原文，
     * 而是带首尾引号、换行写成字面 {@code \n} 两个字符的 JSON 字符串。
     * 直接显示的话，一整张表格会糊成一行，中间夹着一堆 {@code \n}。
     *
     * <p>怎么发现的：加详细日志时看到工具返回"只有 1 行"，
     * 但内容明明是张六行的表。打了个探针才看清
     * {@code getText()} 里一个真换行都没有。
     *
     * <p>判断依据是首尾都是双引号。工具返回的都是自然语言或表格，
     * 不会恰好以引号开头结尾，所以这个判断够用。
     */
    static String decodeIfJsonEncoded(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        if (!text.startsWith("\"") || !text.endsWith("\"")) {
            return text;
        }
        return unescapeJsonString(text.substring(1, text.length() - 1));
    }

    /** 处理 JSON 字符串里的转义序列。 */
    static String unescapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            // 反斜杠加 u 后面不是合法的十六进制，原样保留
                            // （注意不能在这里写出那个转义本身：Java 连注释里的
                            //  它会当成 Unicode 转义，直接编译不过）
                            sb.append('\\').append('u');
                        }
                    } else {
                        sb.append('\\').append('u');
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }

    private static String preview(String text) {
        return text.length() <= PREVIEW_LIMIT
                ? text
                : text.substring(0, PREVIEW_LIMIT) + "\n……（内容较长，已截断）";
    }

    /** 多行内容缩进四格，和日志前缀分得开。 */
    private static String indent(String text) {
        return "    " + text.replace("\n", "\n    ");
    }
}
