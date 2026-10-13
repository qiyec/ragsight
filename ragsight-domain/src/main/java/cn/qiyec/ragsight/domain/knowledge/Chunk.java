package cn.qiyec.ragsight.domain.knowledge;

import java.util.Optional;

/**
 * 文档片段——检索的最小单位。
 *
 * <p>诊断的核心对象之一。切分粒度过大或过小都会直接反映在检索指标上，
 * 因此 Chunk 的长度是诊断报告的重要统计维度。
 *
 * @param id      片段标识
 * @param docId   所属文档
 * @param ordinal 在文档内的序号（与 {@code id} 中的序号一致）
 * @param text    片段正文
 * @param heading 片段所属的标题路径（如 {@code "3.2 计费规则"}），可为空
 */
public record Chunk(ChunkId id, DocumentId docId, int ordinal, String text, Optional<String> heading) {

    public Chunk {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (docId == null) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal 不能为负数：" + ordinal);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("片段正文不能为空：" + id);
        }
        if (heading == null) {
            heading = Optional.empty();
        }
    }

    /** 便捷构造：无标题信息。 */
    public Chunk(ChunkId id, DocumentId docId, int ordinal, String text) {
        this(id, docId, ordinal, text, Optional.empty());
    }

    /** 片段字符长度。诊断报告用它统计切分粒度分布。 */
    public int length() {
        return text.length();
    }

    /**
     * 返回带标题路径的副本。
     *
     * <p>切分策略如果在切分时能识别结构（如 Markdown 标题），可用它补充元数据。
     */
    public Chunk withHeading(String headingPath) {
        return new Chunk(id, docId, ordinal, text, Optional.ofNullable(headingPath));
    }
}
