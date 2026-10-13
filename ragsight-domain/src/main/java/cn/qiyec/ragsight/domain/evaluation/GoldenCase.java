package cn.qiyec.ragsight.domain.evaluation;

import cn.qiyec.ragsight.domain.knowledge.ChunkId;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 标注用例——评估的基准事实。
 *
 * <p><b>为什么需要显式标注，而不能让 LLM 自动判断</b>：
 * 评估集是整个诊断体系的"标尺"。如果标尺本身由 LLM 生成，那么
 * 诊断结论就建立在不可复现的判断之上，工具立刻失去可信度（见 ADR-001）。
 * 标注只做一次，之后可以无限次复用来度量任意配置。
 *
 * @param id            用例标识
 * @param question      用户问题
 * @param expectedChunkIds 期望被召回的证据片段。<b>允许为空集</b>，表示"这个
 *                      问题本就不该有答案"（负样本）。
 * @param referenceAnswer 参考答案，可为空。仅用于生成侧评估（P2+），检索侧不需要。
 * @param note          备注，例如标注理由，可为空
 */
public record GoldenCase(
        String id,
        String question,
        Set<ChunkId> expectedChunkIds,
        Optional<String> referenceAnswer,
        Optional<String> note) {

    public GoldenCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("用例 id 不能为空");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空：" + id);
        }
        if (expectedChunkIds == null) {
            throw new IllegalArgumentException("expectedChunkIds 不能为 null，无答案请传空集：" + id);
        }
        expectedChunkIds = Set.copyOf(expectedChunkIds);
        referenceAnswer = referenceAnswer == null ? Optional.empty() : referenceAnswer;
        note = note == null ? Optional.empty() : note;
    }

    /** 便捷构造：只有期望片段，无参考答案与备注。 */
    public static GoldenCase of(String id, String question, List<ChunkId> expected) {
        return new GoldenCase(id, question, Set.copyOf(expected), Optional.empty(), Optional.empty());
    }

    /**
     * 是否为负样本。
     *
     * <p><b>负样本为什么必须有</b>：如果评测集全是"能答出来的问题"，
     * 那么一个"永远返回最相似内容"的检索器也能拿高分。负样本是检测
     * 系统是否会"硬凑答案"的唯一手段，缺失负样本的评测集是不完整的。
     */
    public boolean isNegative() {
        return expectedChunkIds.isEmpty();
    }

    /** 期望证据数量。 */
    public int expectedCount() {
        return expectedChunkIds.size();
    }
}
