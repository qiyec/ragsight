package cn.qiyec.ragsight.diagnosis;

import cn.qiyec.ragsight.infra.demo.DemoCorpus;
import cn.qiyec.ragsight.infra.demo.DemoEvaluationSet;
import cn.qiyec.ragsight.infra.reader.CorpusPreparation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 页面上能选的语料。
 *
 * <p>两种来源：
 * <ol>
 *   <li><b>内置演示语料</b>——不用准备任何数据，打开页面就能试</li>
 *   <li><b>示例目录</b>——仓库 {@code examples/} 下配好了测试集的目录</li>
 * </ol>
 *
 * <p><b>为什么不做文件上传</b>：上传要处理临时目录、清理、大小限制、
 * 路径穿越。这些都是和"诊断 RAG"无关的工程量。对演示和自用来说，
 * 能选目录已经够了；真要传自己的文档，命令行有 {@code --corpus}。
 *
 * <p>两种来源最终都走 {@link CorpusPreparation}，所以演示路径和真实路径
 * 是同一套流程——演示能跑通就说明真实路径也能跑通。
 */
public class CorpusCatalog {

    private static final Logger log = LoggerFactory.getLogger(CorpusCatalog.class);

    /** 内置演示语料的 id。 */
    public static final String DEMO_ID = "demo";

    /** 仓库示例语料的目录名，用来决定页面默认选哪个。 */
    private static final String EXAMPLE_DIR_NAME = "corpus";

    private final Map<String, CorpusEntry> entries = new LinkedHashMap<>();

    public CorpusCatalog(String examplesDir) {
        Path examplesRoot = Path.of(examplesDir).toAbsolutePath().normalize();

        // 内置演示语料总是有
        entries.put(DEMO_ID, new CorpusEntry(
                DEMO_ID,
                "内置演示语料",
                "虚构的 SaaS 产品帮助中心，4 篇文档。不用准备任何数据就能试。",
                () -> CorpusPreparation.from(DemoCorpus.reader(), DemoEvaluationSet.build())));

        loadExampleDirectories(examplesRoot);
        log.info("可用语料 {} 个：{}", entries.size(), entries.keySet());
    }

    /**
     * 扫一遍示例目录，把配好测试集的目录登记进来。
     *
     * <p>找不到示例目录不是错误——可能有人在别的工作目录启动服务。
     * 这种情况下只剩内置演示语料，页面照样能用。
     */
    private void loadExampleDirectories(Path examplesRoot) {
        if (!Files.isDirectory(examplesRoot)) {
            log.info("示例目录不存在，只提供内置演示语料：{}", examplesRoot);
            return;
        }

        try (Stream<Path> dirs = Files.list(examplesRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList()) {

                Path evalFile = findEvalFile(dir);
                if (evalFile == null) {
                    // 没有测试集就算不出指标。与其在页面上列出来再报错，
                    // 不如根本不列。
                    log.info("目录 {} 里没找到测试集文件，跳过", dir.getFileName());
                    continue;
                }

                String id = dir.getFileName().toString();
                entries.put(id, new CorpusEntry(
                        id,
                        "示例：" + id,
                        "仓库 " + dir.getFileName() + "/ 下的文档，配套测试集 "
                                + evalFile.getFileName(),
                        () -> CorpusPreparation.from(dir, evalFile)));
            }
        } catch (IOException e) {
            log.warn("扫描示例目录失败，只提供内置演示语料：{}", e.getMessage());
        }
    }

    /**
     * 找某个语料目录配套的测试集文件。
     *
     * <p>在两处找：目录自己里面、以及它的上一层。后者是因为仓库里的示例
     * 组织成 {@code examples/corpus/} 放文档、{@code examples/eval.txt} 放测试集
     * ——测试集和文档目录平级，不在里面。
     */
    private Path findEvalFile(Path dir) {
        Path own = findEvalIn(dir);
        if (own != null) {
            return own;
        }
        Path parent = dir.getParent();
        if (parent == null) {
            return null;
        }
        // 上一层找时，只认最标准的 eval.txt——避免把无关的 txt 当成测试集。
        // 比如 examples/eval-format.txt 是格式说明，不是测试集。
        Path standard = parent.resolve("eval.txt");
        return Files.isRegularFile(standard) ? standard : null;
    }

    /** 在目录内部找测试集文件：eval.txt 优先，其次任意 *.eval.txt。 */
    private Path findEvalIn(Path dir) {
        Path preferred = dir.resolve("eval.txt");
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".eval.txt"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 所有可选的语料。 */
    public List<CorpusEntry> all() {
        return List.copyOf(entries.values());
    }

    /** 按 id 取语料；不存在返回 null。 */
    public CorpusEntry byId(String id) {
        return entries.get(id);
    }

    /** 页面默认选哪个语料：优先仓库里那个真实一点的示例。 */
    public String defaultId() {
        return entries.containsKey(EXAMPLE_DIR_NAME) ? EXAMPLE_DIR_NAME : DEMO_ID;
    }

    /**
     * 一个可选的语料。
     *
     * <p>存的是"怎么读"而不是"读好的结果"，所以每次用都是现读的。
     * 这样改了测试集文件之后刷新页面就能看到新结果，
     * 不会出现"改了没生效"的困惑。
     */
    public record CorpusEntry(String id,
                              String displayName,
                              String description,
                              Supplier<CorpusPreparation> loader) {

        /** 真的去读一遍。读失败时抛出带上下文的错误。 */
        public CorpusPreparation load() {
            try {
                return loader.get();
            } catch (UncheckedIOException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new UncheckedIOException(new IOException(String.format(
                        "读取语料「%s」失败：%s", displayName, e.getMessage()), e));
            }
        }
    }
}
