package cn.qiyec.ragsight.agent;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * 诊断 Agent 的命令行入口。
 *
 * <p>用法：
 * <pre>
 *   # DashScope 的凭据放环境变量，AgentScope 自己会读
 *   set DASHSCOPE_API_KEY=你的key
 *   java -jar ragsight-agent/target/ragsight-agent-exec.jar diagnose
 * </pre>
 *
 * <p>为什么单独一个可执行 jar，而不是并进 ragsight-cli：
 * 架构规则要求 cli 和 agent 互不依赖（它们是平级的两个入口）。
 * 并进去就得让 cli 依赖 agent，那条规则就破了。
 * 代价是多一个 jar，换来的是"不跑大模型的那条路完全不依赖 AgentScope"。
 */
@Command(
        name = "ragsight-agent",
        mixinStandardHelpOptions = true,
        version = "ragsight-agent 0.1.0",
        description = """
                会自己决定下一步试什么的 RAG 诊断 Agent。

                它一次只改一个东西、看效果、再决定下一个改什么，
                通常 3 到 4 轮就能定位瓶颈，而不是把几十种组合跑一遍。

                需要大模型凭据（DashScope 读环境变量 DASHSCOPE_API_KEY）。
                不想用大模型的话，命令行的 diagnose 和网页都不需要它。
                """,
        subcommands = AgentCli.DiagnoseAgentCommand.class)
public class AgentCli implements Runnable {

    @Override
    public void run() {
        // 不带子命令时打出帮助，而不是静默什么都不做
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int code = new CommandLine(new AgentCli()).execute(args);
        System.exit(code);
    }

    /** 真正干活的子命令。 */
    @Command(
            name = "diagnose",
            mixinStandardHelpOptions = true,
            description = "让 Agent 自己探索，找出瓶颈在哪一步")
    static class DiagnoseAgentCommand implements Callable<Integer> {

        @Option(names = {"-c", "--corpus"},
                description = "语料 id。不填就用默认那个（见 --list）")
        private String corpusId;

        @Option(names = {"--examples-dir"},
                description = "示例语料目录（默认 ${DEFAULT-VALUE}）",
                defaultValue = "examples")
        private String examplesDir;

        @Option(names = {"-k", "--top-k"},
                description = "召回条数（默认 ${DEFAULT-VALUE}）",
                defaultValue = "5")
        private int topK;

        @Option(names = {"-m", "--model"},
                description = "大模型，格式 厂商:模型名（默认 ${DEFAULT-VALUE}）",
                defaultValue = RagsightDiagnosisAgent.DEFAULT_MODEL)
        private String model;

        @Option(names = {"--max-iters"},
                description = "最多做多少轮推理-工具循环（默认 ${DEFAULT-VALUE}）",
                defaultValue = "" + RagsightDiagnosisAgent.DEFAULT_MAX_ITERS)
        private int maxIters;

        @Option(names = {"-q", "--quiet"},
                description = "只打最终结论，不显示中间过程")
        private boolean quiet;

        @Option(names = {"--list"},
                description = "列出可用的语料，然后退出")
        private boolean listOnly;

        @Override
        public Integer call() {
            if (listOnly) {
                return listCorpora();
            }

            String key = System.getenv("DASHSCOPE_API_KEY");
            if (!model.startsWith("ollama:") && (key == null || key.isBlank())) {
                System.err.println("""
                        没有找到大模型凭据。

                        这个命令需要调用大模型来决定"下一步试什么"。
                        请先设置对应厂商的环境变量，例如 DashScope：
                          Windows PowerShell:  $env:DASHSCOPE_API_KEY = "你的key"
                          Linux / macOS:       export DASHSCOPE_API_KEY=你的key

                        如果你不想用大模型：
                          - 命令行诊断：java -jar ragsight-cli/target/ragsight-cli.jar diagnose
                          - 网页界面：  java -jar ragsight-web/target/ragsight-web-exec.jar
                        这两个都不需要任何 Key。
                        """);
                return 2;
            }

            DiagnosisRun run;
            try {
                run = DiagnosisRun.builder()
                        .examplesDir(examplesDir)
                        .corpusId(corpusId)
                        .topK(topK)
                        .model(model)
                        .maxIters(maxIters)
                        .progressListener(new ConsoleProgress(quiet))
                        .build();
            } catch (IllegalArgumentException e) {
                System.err.println("参数有误：" + e.getMessage());
                return 1;
            }

            System.out.printf("开始诊断（模型 %s，最多 %d 轮）%n%n", model, maxIters);

            long startedAt = System.currentTimeMillis();
            String conclusion;
            try {
                conclusion = run.execute();
            } catch (RagsightDiagnosisAgent.AgentRunException e) {
                System.err.println();
                System.err.println("诊断失败：" + e.getMessage());
                System.err.println("凭据是否正确？模型名是否可用？网络通不通？");
                return 1;
            }
            long elapsed = System.currentTimeMillis() - startedAt;

            printSummary(run.summary(), conclusion, elapsed);
            return 0;
        }

        private Integer listCorpora() {
            var catalog = new cn.qiyec.ragsight.diagnosis.CorpusCatalog(examplesDir);
            System.out.println("可用语料：");
            for (var entry : catalog.all()) {
                System.out.printf("  %-12s %s%n", entry.id(), entry.displayName());
                System.out.printf("  %-12s %s%n", "", entry.description());
            }
            System.out.printf("%n默认会用：%s%n", catalog.defaultId());
            return 0;
        }

        private void printSummary(DiagnosisRun.Summary summary,
                                  String conclusion,
                                  long elapsedMillis) {
            System.out.println();
            System.out.println("========== Agent 的总结 ==========");
            System.out.println(conclusion == null || conclusion.isBlank()
                    ? "（模型没有给出总结）" : conclusion);

            System.out.println();
            System.out.println("========== 硬数据 ==========");
            System.out.printf("语料：%s%n", summary.corpusLabel());
            System.out.printf("一共跑了 %d 次实验，试过 %d 个配置，用时 %.1f 秒%n",
                    summary.experimentCount(), summary.configsTried(),
                    elapsedMillis / 1000.0);
            if (summary.bestConfig() != null) {
                System.out.printf("最好的配置：%s%n", summary.bestConfig());
                System.out.printf("  Recall %.3f   MRR %.3f%n",
                        summary.bestRecall(), summary.bestMrr());
            }
            // 这里原本还有一句"穷举全部组合要跑 18 次"——删掉了。
            // 一是那个数写死了（只对某个切分窗口成立），二是它和
            // 上面 finish 工具已经算好的对照重复，而且上面那个是对的。
            // 两处说同一件事、其中一处还是错的，比不说更糟。

            if (!summary.engineFindings().isEmpty()) {
                System.out.println();
                System.out.println("========== 诊断引擎的结论 ==========");
                summary.engineFindings().stream().distinct()
                        .forEach(f -> System.out.println("  · " + f));
                System.out.println();
                System.out.println("这些结论由固定规则从指标推导，不经过大模型——");
                System.out.println("所以同样的数据每次都会得到同样的说法。");
            }
        }
    }

    /** 把 Agent 的每一步打到控制台。 */
    private static final class ConsoleProgress
            implements RagsightDiagnosisAgent.ProgressListener {

        private final boolean quiet;

        ConsoleProgress(boolean quiet) {
            this.quiet = quiet;
        }

        @Override
        public void onStep(String message) {
            if (!quiet) {
                System.out.println("[步骤] " + oneLine(message));
            }
        }

        @Override
        public void onToolCall(String toolName, String arguments) {
            if (!quiet) {
                System.out.printf("[调用] %s(%s)%n", toolName, arguments);
            }
        }

        @Override
        public void onToolResult(String toolName, String result) {
            if (!quiet) {
                // 工具返回的内容很长（是给模型看的表格），
                // 控制台上不需要全打，给个首行让它有个进度感
                System.out.println("       ↓ " + firstLine(result));
            }
        }

        @Override
        public void onError(String message) {
            System.err.println("[出错] " + message);
        }

        private static String oneLine(String text) {
            String flat = text.replaceAll("\\s+", " ").strip();
            return flat.length() <= 100 ? flat : flat.substring(0, 100) + "…";
        }

        private static String firstLine(String text) {
            if (text == null || text.isBlank()) {
                return "(空)";
            }
            String[] lines = text.split("\\R");
            for (String line : lines) {
                if (!line.isBlank()) {
                    return oneLine(line);
                }
            }
            return "(空)";
        }
    }
}
