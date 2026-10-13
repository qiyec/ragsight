package cn.qiyec.ragsight.domain.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * 待检索的原始文档。
 *
 * <p>这是"知识库"限界上下文的核心实体，代表切分**之前**的完整文档。
 * 切分后的片段见 {@link Chunk}。
 *
 * @param id      文档标识
 * @param title   文档标题，可为空
 * @param content 正文全文，不可为空
 * @param source  来源标识（文件路径 / URL），可为空
 */
public record Document(DocumentId id, String title, String content, Optional<String> source) {

    public Document {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空白文档：" + id);
        }
        if (source == null) {
            source = Optional.empty();
        }
        title = (title == null || title.isBlank()) ? id.value() : title;
    }

    /**
     * 便捷构造：无来源信息。
     */
    public static Document of(String id, String content) {
        return new Document(DocumentId.of(id), null, content, Optional.empty());
    }

    /**
     * 按行切分正文。
     *
     * <p>切分策略需要按段落/行边界处理文本时使用。返回的列表不包含行尾换行符，
     * 且会保留空行（空行是段落边界信息，不应丢失）。
     */
    public List<String> lines() {
        return content.lines().toList();
    }

    /**
     * 正文长度（字符数）。用于诊断报告中的语料统计。
     */
    public int length() {
        return content.length();
    }

    /**
     * 把一组文本片段装配成本文档的 {@link Chunk} 列表。
     *
     * <p>这是本上下文的**不变式**所在地：所有切分策略都必须通过它产出 Chunk，
     * 从而保证 ordinal 连续、正文被 trim、空片段被剔除。
     * 这样各个切分策略自身不需要重复实现这些规则。
     *
     * @param texts 切分出的文本片段，允许含空白片段（会被剔除）
     * @return 序号从 0 连续递增、且不含空白片段的 Chunk 列表
     */
    public List<Chunk> toChunks(List<String> texts) {
        List<String> nonBlank = texts.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::strip)
                .toList();

        return IntStream.range(0, nonBlank.size())
                .mapToObj(i -> new Chunk(ChunkId.of(id.value(), i), id, i, nonBlank.get(i)))
                .toList();
    }
}
