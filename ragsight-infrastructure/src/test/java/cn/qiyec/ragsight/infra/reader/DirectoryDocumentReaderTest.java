package cn.qiyec.ragsight.infra.reader;

import cn.qiyec.ragsight.domain.knowledge.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("从目录读文档")
class DirectoryDocumentReaderTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("应读出支持的文档，并按路径排序")
    void should_read_supported_files_in_stable_order(@TempDir Path dir) throws IOException {
        write(dir.resolve("b.md"), "# B\n内容");
        write(dir.resolve("a.md"), "# A\n内容");
        write(dir.resolve("c.txt"), "纯文本内容");

        List<Document> documents = new DirectoryDocumentReader(dir).readAll();

        // 顺序必须稳定：顺序影响片段编号，编号影响报告
        assertThat(documents).extracting(d -> d.id().value())
                .containsExactly("a.md", "b.md", "c.txt");
    }

    @Test
    @DisplayName("不支持的扩展名应被忽略")
    void should_ignore_unsupported_extensions(@TempDir Path dir) throws IOException {
        write(dir.resolve("readme.md"), "内容");
        write(dir.resolve("image.png"), "假装是图片");
        write(dir.resolve("data.json"), "{}");

        List<Document> documents = new DirectoryDocumentReader(dir).readAll();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).id().value()).isEqualTo("readme.md");
    }

    @Test
    @DisplayName("文档 id 用相对路径，报告里才能指出是哪个文件")
    void document_id_should_be_relative_path(@TempDir Path dir) throws IOException {
        write(dir.resolve("guide").resolve("billing.md"), "# 计费\n内容");

        List<Document> documents = new DirectoryDocumentReader(dir).readAll();

        assertThat(documents).hasSize(1);
        // 统一用 / 分隔，这样 Windows 和 Linux 上 id 一样，
        // 测试集里写的期望文字才能跨平台通用
        assertThat(documents.get(0).id().value()).isEqualTo("guide/billing.md");
    }

    @Test
    @DisplayName("标题优先取第一个 Markdown 标题")
    void title_should_come_from_first_heading(@TempDir Path dir) throws IOException {
        write(dir.resolve("doc.md"), "# 计费规则说明\n\n正文内容");

        Document document = new DirectoryDocumentReader(dir).readAll().get(0);

        assertThat(document.title()).isEqualTo("计费规则说明");
    }

    @Test
    @DisplayName("没有标题时用文件名当标题")
    void title_should_fallback_to_file_name(@TempDir Path dir) throws IOException {
        write(dir.resolve("plain-notes.md"), "这里没有标题\n直接是正文");

        Document document = new DirectoryDocumentReader(dir).readAll().get(0);

        assertThat(document.title()).isEqualTo("plain-notes");
    }

    @Test
    @DisplayName("应处理 UTF-8 BOM")
    void should_strip_bom(@TempDir Path dir) throws IOException {
        // BOM 是 Windows 编辑器常留的坑：会让第一个字符变成看不见的怪字符，
        // 导致按标题切分时第一行匹配不上
        Files.writeString(dir.resolve("with-bom.md"),
                "\uFEFF# 计费规则\n正文", StandardCharsets.UTF_8);

        Document document = new DirectoryDocumentReader(dir).readAll().get(0);

        assertThat(document.title()).isEqualTo("计费规则");
        assertThat(document.content()).startsWith("#");
    }

    @Test
    @DisplayName("应统一换行符")
    void should_normalize_line_endings(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("crlf.md"), "# 标题\r\n第一行\r\n第二行",
                StandardCharsets.UTF_8);

        Document document = new DirectoryDocumentReader(dir).readAll().get(0);

        assertThat(document.content()).doesNotContain("\r");
    }

    @Test
    @DisplayName("空白文件应被跳过，不产生空文档")
    void should_skip_blank_files(@TempDir Path dir) throws IOException {
        write(dir.resolve("blank.md"), "   \n\n  \n");
        write(dir.resolve("real.md"), "有内容");

        List<Document> documents = new DirectoryDocumentReader(dir).readAll();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).id().value()).isEqualTo("real.md");
    }

    @Test
    @DisplayName("空目录返回空列表，不报错")
    void should_return_empty_for_empty_directory(@TempDir Path dir) {
        assertThat(new DirectoryDocumentReader(dir).readAll()).isEmpty();
    }

    @Test
    @DisplayName("目录不存在或不是目录时应立刻报错")
    void should_reject_invalid_directory(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("nope");
        assertThatThrownBy(() -> new DirectoryDocumentReader(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目录不存在");

        Path aFile = dir.resolve("file.md");
        write(aFile, "内容");
        assertThatThrownBy(() -> new DirectoryDocumentReader(aFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是一个目录");
    }

    @Test
    @DisplayName("同一个目录读两次结果必须一致")
    void should_be_reproducible(@TempDir Path dir) throws IOException {
        write(dir.resolve("a.md"), "# A\n内容一");
        write(dir.resolve("b.md"), "# B\n内容二");

        DirectoryDocumentReader reader = new DirectoryDocumentReader(dir);

        assertThat(reader.readAll().stream().map(d -> d.id().value()).toList())
                .isEqualTo(reader.readAll().stream().map(d -> d.id().value()).toList());
    }

    @Test
    @DisplayName("来源描述应说明读的是哪个目录")
    void describe_should_mention_the_directory(@TempDir Path dir) {
        assertThat(new DirectoryDocumentReader(dir).describe()).contains("目录");
    }

    @Test
    @DisplayName("可以自定义支持的扩展名")
    void should_support_custom_extensions(@TempDir Path dir) throws IOException {
        write(dir.resolve("a.md"), "Markdown");
        write(dir.resolve("b.log"), "日志内容");

        List<Document> documents = new DirectoryDocumentReader(dir, java.util.Set.of(".log"))
                .readAll();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).id().value()).isEqualTo("b.log");
    }
}
