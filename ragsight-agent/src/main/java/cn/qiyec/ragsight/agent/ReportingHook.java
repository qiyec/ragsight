package cn.qiyec.ragsight.agent;

import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 把 Agent 的执行过程报给界面。
 *
 * <p>为什么需要它：调 Agent 最难受的地方是**它闷头想半天没动静**，
 * 你分不清它是在正常工作还是卡住了。有了实时上报，
 * 界面上能看到"正在调用 compare_one_change"、"这一步改的是 rerank"，
 * 等待就有了意义。
 *
 * <p>用 AgentScope 的 Hook 机制，而不是自己包一层 Agent。
 * 理由是 Hook 是框架提供的观察点，包一层就得处理
 * Agent 的泛型和生命周期，还容易和框架的调用方式脱节。
 *
 * <p><b>这个类不改执行结果。</b> 所有回调都把原事件原样返回，
 * 只是"顺路看一眼"。任何试图在这里修改事件的写法都会让调试变困难——
 * 一旦行为出问题，你分不清是 Agent 本身还是 Hook 干的。
 */
final class ReportingHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ReportingHook.class);

    /** 工具返回内容太长时截断，界面上不需要看几万字的表格。 */
    private static final int RESULT_PREVIEW_LIMIT = 900;

    private final RagsightDiagnosisAgent.ProgressListener listener;
    private int step;

    ReportingHook(RagsightDiagnosisAgent.ProgressListener listener) {
        this.listener = listener;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            dispatch(event);
        } catch (RuntimeException e) {
            // 上报失败绝不能影响诊断本身。记一笔继续跑。
            log.warn("上报进度时出错（不影响诊断）：{}", e.getMessage());
        }
        return Mono.just(event);
    }

    private <T extends HookEvent> void dispatch(T event) {
        if (event instanceof PreActingEvent pre) {
            ToolUseBlock tool = pre.getToolUse();
            if (tool != null) {
                step++;
                listener.onStep("第 " + step + " 步");
                listener.onToolCall(tool.getName(), describeInput(tool));
            }
        } else if (event instanceof PostActingEvent post) {
            ToolResultBlock result = post.getToolResult();
            if (result != null) {
                listener.onToolResult(result.getName(), textOf(result));
            }
        } else if (event instanceof PostReasoningEvent reasoning) {
            // 模型这一步的思考。有文字才报，纯工具调用时是空的。
            var msg = reasoning.getReasoningMessage();
            if (msg != null) {
                String text = msg.getTextContent();
                if (text != null && !text.isBlank()) {
                    listener.onStep(text.strip());
                }
            }
        } else if (event instanceof ErrorEvent error) {
            Throwable cause = error.getError();
            listener.onError(cause == null ? "未知错误" : String.valueOf(cause.getMessage()));
        }
    }

    /** 把工具入参渲染成一行好读的文字。 */
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

    /** 把工具返回的内容拼成文字。 */
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
            // 不是文本块（比如结构化结果），退回用 toString。
            // 这种很少见，能看个大概就行。
            text = output.toString();
        }
        return text.length() <= RESULT_PREVIEW_LIMIT
                ? text
                : text.substring(0, RESULT_PREVIEW_LIMIT) + "\n……（内容较长，已截断）";
    }
}
