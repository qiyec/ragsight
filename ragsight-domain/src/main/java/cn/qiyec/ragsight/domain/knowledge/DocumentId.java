package cn.qiyec.ragsight.domain.knowledge;

/**
 * 文档标识。
 *
 * <p>不可变，构造时就校验参数。
 */
public record DocumentId(String value) {

    public DocumentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("DocumentId 不能为空");
        }
    }

    public static DocumentId of(String value) {
        return new DocumentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
