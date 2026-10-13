package cn.qiyec.ragsight.domain.knowledge;

/**
 * 文档片段的标识。
 *
 * <p>由「所属文档 + 序号」唯一确定。使用复合标识而非随机 UUID，是为了让诊断结果
 * **可复现且可读**——报告中出现 {@code doc-1#7} 比一串 UUID 更容易定位问题。
 *
 * @param documentId 所属文档
 * @param ordinal    在文档内的序号，从 0 开始
 */
public record ChunkId(DocumentId documentId, int ordinal) {

    public ChunkId {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal 不能为负数：" + ordinal);
        }
    }

    public static ChunkId of(String documentId, int ordinal) {
        return new ChunkId(DocumentId.of(documentId), ordinal);
    }

    /** 形如 {@code doc-1#7} 的稳定可读标识。 */
    @Override
    public String toString() {
        return documentId.value() + "#" + ordinal;
    }
}
