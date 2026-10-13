package cn.qiyec.ragsight.domain.knowledge.chunking;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定窗口切分：按固定字符数切，窗口之间保留重叠。
 *
 * <p><b>为什么需要重叠（overlap）</b>：如果答案恰好横跨两个窗口的边界，
 * 无重叠的切分会把它截断，导致任何检索手段都召不回完整语义。
 * 重叠让跨越边界的句子至少在一个窗口里保持完整。
 *
 * <p><b>重叠的代价</b>：同一段文字被存多份，向量库体积和检索结果重复率都上升。
 * 重叠过大还会出现"一个片段挤占多个 topK 名额"的同质化问题——
 * 这正是诊断工具要量化的事情，因此 overlap 必须是可调参数而非写死。
 *
 * <p>本策略的典型缺陷：**完全不理解语义边界**，可能在句子中间、表格中间切断。
 * 它是切分实验的基线（baseline），其他策略必须证明比它更好。
 */
public final class FixedWindowChunkingStrategy implements ChunkingStrategy {

    private final int windowSize;

    private final int overlap;

    /**
     * @param windowSize 窗口字符数，必须为正
     * @param overlap    相邻窗口重叠字符数，必须非负且小于 windowSize
     */
    public FixedWindowChunkingStrategy(int windowSize, int overlap) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize 必须为正数，实际为 " + windowSize);
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数，实际为 " + overlap);
        }
        if (overlap >= windowSize) {
            throw new IllegalArgumentException(
                    "overlap(" + overlap + ") 必须小于 windowSize(" + windowSize + ")，否则窗口无法前进");
        }
        this.windowSize = windowSize;
        this.overlap = overlap;
    }

    @Override
    public String name() {
        return "fixed-window";
    }

    @Override
    public String describe() {
        return "固定窗口 window=" + windowSize + " overlap=" + overlap;
    }

    /**
     * 步长 = 窗口大小 - 重叠。因为已校验 overlap &lt; windowSize，步长恒为正，循环必然终止。
     */
    private int step() {
        return windowSize - overlap;
    }

    @Override
    public List<Chunk> chunk(Document document) {
        String content = document.content();
        List<String> texts = new ArrayList<>();

        for (int start = 0; start < content.length(); start += step()) {
            int end = Math.min(start + windowSize, content.length());
            texts.add(content.substring(start, end));
            if (end == content.length()) {
                break;
            }
        }
        return document.toChunks(texts);
    }

    public int windowSize() {
        return windowSize;
    }

    public int overlap() {
        return overlap;
    }
}
