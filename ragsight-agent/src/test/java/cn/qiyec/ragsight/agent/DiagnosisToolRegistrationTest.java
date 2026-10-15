package cn.qiyec.ragsight.agent;

import cn.qiyec.ragsight.diagnosis.CorpusCatalog;
import cn.qiyec.ragsight.diagnosis.DiagnosisService;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具注册的测试。
 *
 * <p><b>为什么这件事必须单独测</b>：工具是靠 {@code @Tool} 注解反射注册的。
 * 如果注解没被识别、参数没被推导成 schema，**模型看到的工具列表就是空的，
 * 于是它只会聊天、不会去跑任何实验**——而且不会有任何报错。
 * 这种"静默失效"最难查，所以要在不需要大模型的情况下先用测试钉住。
 *
 * <p>这里完全不调模型，只检查注册出来的东西长什么样。
 */
@DisplayName("诊断工具的注册")
class DiagnosisToolRegistrationTest {

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        // 不存在的目录 -> 只用内存里的演示语料，测试不受仓库文件影响
        CorpusCatalog catalog = new CorpusCatalog(
                Path.of("不存在的目录-" + System.nanoTime()).toString());
        DiagnosisSession session = new DiagnosisSession(
                CorpusCatalog.DEMO_ID, "演示语料", 5, 18,
                new DiagnosisService(catalog),
                new DiagnosisService.ApiRequest(
                        CorpusCatalog.DEMO_ID, null, null, 5, false, false, null, null));

        toolkit = RagsightDiagnosisAgent.buildToolkit(session);
    }

    @Test
    @DisplayName("四个工具都应该注册上")
    void should_register_all_four_tools() {
        Set<String> names = toolkit.getToolNames();

        assertThat(names).contains(
                "run_baseline",
                "compare_one_change",
                "list_attempts",
                "finish");
    }

    @Test
    @DisplayName("每个工具都要有给模型看的说明，不能是空的")
    void every_tool_should_have_description() {
        List<ToolSchema> schemas = toolkit.getToolSchemas();

        assertThat(schemas).isNotEmpty();
        for (ToolSchema schema : schemas) {
            assertThat(schema.getName()).isNotBlank();
            assertThat(schema.getDescription())
                    .as("工具 %s 的说明", schema.getName())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("compare_one_change 的两个参数要带说明——模型全靠它理解该填什么")
    void compare_one_change_should_expose_parameters() {
        ToolSchema schema = findSchema("compare_one_change");

        assertThat(schema.getParameters()).isNotNull();
        String json = schema.getParameters().toString();

        // 两个参数名都要出现
        assertThat(json).contains("change");
        assertThat(json).contains("new_value");

        // 说明也要出现，否则模型不知道 change 能填哪些值
        assertThat(json).contains("chunk_size");
        assertThat(json).contains("retriever");
        assertThat(json).contains("rerank");
    }

    @Test
    @DisplayName("run_baseline 要暴露出 chunk_size 参数")
    void run_baseline_should_expose_chunk_size() {
        ToolSchema schema = findSchema("run_baseline");

        String json = schema.getParameters().toString();
        assertThat(json).contains("chunk_size");
    }

    @Test
    @DisplayName("没有参数的工具不该被塞进一堆必填项")
    void parameterless_tools_should_be_callable_as_is() {
        ToolSchema schema = findSchema("finish");

        // finish 没有参数，schema 里不该冒出 required 字段
        Map<String, Object> params = schema.getParameters();
        if (params != null && params.containsKey("required")) {
            Object required = params.get("required");
            assertThat(required instanceof List ? (List<?>) required : List.of())
                    .as("finish 不需要任何参数")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("工具说明里要写清「一次只改一个」这个工作方式")
    void compare_tool_description_should_state_the_rule() {
        ToolSchema schema = findSchema("compare_one_change");

        // 这条要求写在工具说明里，模型才看得到。
        // 它是"不穷举"这个目标能不能成立的关键——如果模型
        // 以为可以一次改好几个，归因就失效了。
        assertThat(schema.getDescription()).contains("只改一个");
    }

    private ToolSchema findSchema(String name) {
        return toolkit.getToolSchemas().stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "没有注册这个工具：" + name
                                + "，已注册的是 " + toolkit.getToolNames()));
    }
}
