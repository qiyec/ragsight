package cn.qiyec.ragsight.infra.reader;

import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.DocumentId;
import cn.qiyec.ragsight.domain.knowledge.DocumentReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 从一个目录读文档：默认读里面的 Markdown 和纯文本文件。
 *
 * <p>几个刻意的选择：
 *
 * <p><b>文档 id 用相对路径</b>，比如 {@code guide/billing.md}。
 * 这样报告里能直接指出"是哪个文件的哪一段"，而不用去猜。
 * 用序号当 id 虽然更短，但出了问题定位不到文件，等于白诊断。
 *
 * <p><b>按路径排序</b>，保证同一个目录读两次顺序一致。
 * 顺序影响片段编号，编号影响报告——不排序的话结果就不稳定了。
 *
 * <p><b>单个文件读失败就跳过</b>，不让一个坏文件把整批都搞崩。
 * 但会记日志说明跳过了哪个，避免静默丢数据。
 */
public final class DirectoryDocumentReader implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(DirectoryDocumentReader.class);

    /** 默认识别的扩展名。 */
    public static final Set<String> DEFAULT_EXTENSIONS = Set.of(".md", ".markdown", ".txt");

    private final Path root;
    private final Set<String> extensions;

    public DirectoryDocumentReader(Path root) {
        this(root, DEFAULT_EXTENSIONS);
    }

    public DirectoryDocumentReader(Path root, Set<String> extensions) {
        if (root == null) {
            throw new IllegalArgumentException("目录不能为空");
        }
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("目录不存在：" + root.toAbsolutePath());
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("这不是一个目录：" + root.toAbsolutePath());
        }
        if (extensions == null || extensions.isEmpty()) {
            throw new IllegalArgumentException("至少要支持一种扩展名");
        }
        this.root = root.toAbsolutePath().normalize();
        this.extensions = extensions;
    }

    @Override
    public List<Document> readAll() {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(this::hasSupportedExtension)
                    .sorted(Comparator.comparing(p -> relativePath(p)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历目录失败：" + root, e);
        }

        List<Document> documents = new ArrayList<>(files.size());
        for (Path file : files) {
            readOne(file).ifPresent(documents::add);
        }

        if (documents.isEmpty()) {
            log.warn("目录里没有找到可读的文档（支持 {}）：{}",
                    extensions, root);
        }
        return List.copyOf(documents);
    }

    /**
     * 读单个文件。读不了就返回空，由调用方决定怎么处理。
     */
    private Optional<Document> readOne(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String content = normalize(raw);
            if (content.isBlank()) {
                log.warn("跳过空白文件：{}", relativePath(file));
                return Optional.empty();
            }
            String relative = relativePath(file);
            return Optional.of(new Document(
                    DocumentId.of(relative),
                    titleOf(relative, content),
                    content,
                    Optional.of(relative)));
        } catch (IOException e) {
            // 单个文件读失败不中断整批：目录里可能有权限受限的临时文件
            log.warn("跳过读不了的文件 {}：{}", relativePath(file), e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            // 例如内容不符合 Document 的校验规则
            log.warn("跳过内容不合法的文件 {}：{}", relativePath(file), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 统一换行、去掉 BOM。
     *
     * <p>BOM 是 Windows 编辑器常留的坑：它会让文件第一个字符变成一个看不见的怪字符，
     * 导致按标题切分时第一行匹配不上。必须在读进来时就处理掉。
     */
    private static String normalize(String raw) {
        String text = raw;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 取标题：优先用第一个 Markdown 标题，否则用文件名。
     */
    private static String titleOf(String relativePath, String content) {
        for (String line : content.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                String heading = trimmed.replaceAll("^#+\\s*", "").strip();
                if (!heading.isEmpty()) {
                    return heading;
                }
            }
            // 只看开头几行，避免把正文里的 # 误当标题
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                break;
            }
        }
        return fileNameWithoutExtension(relativePath);
    }

    private static String fileNameWithoutExtension(String relativePath) {
        String name = relativePath;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 相对路径，并且统一用 {@code /} 分隔。
     *
     * <p>统一分隔符是为了让文档 id 在不同操作系统下都一样——
     * 否则同一份文档在 Windows 上叫 {@code guide\a.md}，在 Linux 上叫
     * {@code guide/a.md}，测试集里写的期望片段就跨平台对不上了。
     */
    private String relativePath(Path file) {
        String relative = root.relativize(file.toAbsolutePath().normalize()).toString();
        return relative.replace('\\', '/');
    }

    private boolean hasSupportedExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(name::endsWith);
    }

    @Override
    public String describe() {
        return "目录 " + root + "（支持 " + String.join("/", extensions.stream().sorted().toList()) + "）";
    }

    /** 根目录，便于测试断言。 */
    public Path root() {
        return root;
    }
}
