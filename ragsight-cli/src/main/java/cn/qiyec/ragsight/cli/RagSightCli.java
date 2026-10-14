package cn.qiyec.ragsight.cli;

import cn.qiyec.ragsight.cli.command.DiagnoseCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * ragsight 命令行入口。
 *
 * <p>当前只是骨架：确立 CLI 形态与可用性，尚未接入诊断能力。
 * 子命令按阶段逐步加入：
 * <ul>
 *   <li>{@code rag diagnose} —— 对知识库执行阶段诊断（P3）</li>
 *   <li>{@code rag ablate}   —— 跑策略矩阵消融实验（P3）</li>
 *   <li>{@code rag learn}    —— 运行学习文档对应的演示（P4+）</li>
 * </ul>
 */
@Command(
        name = "ragsight",
        mixinStandardHelpOptions = true,
        versionProvider = RagSightCli.ManifestVersionProvider.class,
        description = "RAG 质量诊断与调优工作台：定位检索增强生成的真实瓶颈。",
        subcommands = {DiagnoseCommand.class}
)
public final class RagSightCli implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("ragsight —— RAG 质量诊断与调优工作台");
        System.out.println();
        System.out.println("可用子命令：");
        System.out.println("  diagnose   对知识库执行检索质量诊断，输出指标对比与结论");
        System.out.println();
        System.out.println("快速开始：");
        System.out.println("  ragsight diagnose              # 用内置演示语料跑一次诊断");
        System.out.println("  ragsight diagnose -o report.md # 把报告写入文件");
        System.out.println();
        System.out.println("使用 --help 查看完整选项。项目文档见 AGENTS.md 与 docs/ 目录。");
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RagSightCli()).execute(args);
        System.exit(exitCode);
    }

    /**
     * 从 JAR manifest 读取版本号。
     *
     * <p>由父 POM 中 maven-jar-plugin 的 {@code addDefaultImplementationEntries}
     * 写入，因此 --version 输出的版本与实际构建产物一致，不需要手工维护。
     */
    static final class ManifestVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = RagSightCli.class.getPackage().getImplementationVersion();
            return new String[]{"ragsight " + (version == null ? "(开发版本)" : version)};
        }
    }
}
