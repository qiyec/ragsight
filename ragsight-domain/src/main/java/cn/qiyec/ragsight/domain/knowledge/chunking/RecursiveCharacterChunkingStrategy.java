package cn.qiyec.ragsight.domain.knowledge.chunking;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符切分：按分隔符优先级逐级尝试，尽量在自然边界处切分。
 *
 * <p><b>核心思想</b>：优先在"语义断裂代价最小"的位置切开。分隔符优先级为：
 * 段落 → 换行 → 中文句号/问号/叹号 → 英文句点 → 分号 → 逗号 → 空格 → 硬切。
 * 先用粗粒度分隔符切，若某段仍超过窗口上限，再用更细的分隔符递归处理。
 *
 * <p><b>相比固定窗口的优势</b>：不会在句子中间硬切，保留完整语义单元。
 * <b>相比固定窗口的劣势</b>：片段长度分布不均（可能很短），
 * 且中文标点判断依赖启发式规则，对无标点文本退化为硬切。
 *
 * <p>长度分布的"不均"是本策略的真实风险，也是诊断报告必须输出长度分布直方图的原因——
 * 只看平均长度会掩盖这个问题。
 */
public final class RecursiveCharacterChunkingStrategy implements ChunkingStrategy {

    /**
     * 分隔符优先级，从粗到细。
     *
     * <p>顺序有实际意义：先按段落切能最大程度保留语块完整性；
     * 中文句号排在英文句点前，因为本项目的目标语料以中文为主。
     */
    static final List<String> SEPARATORS = List.of(
            "\n\n",     // 段落
            "\n",       // 换行
            "。", "？", "！",  // 中文句末
            ". ", "? ", "! ",  // 英文句末（带空格，避免切开小数点与缩写）
            "；", ";",  // 分号
            "，", ",",  // 逗号
            " ",        // 空格
            ""          // 兜底：逐字符硬切
    );

    private final int chunkSize;

    private final int overlap;

    public RecursiveCharacterChunkingStrategy(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须为正数，实际为 " + chunkSize);
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数，实际为 " + overlap);
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap(" + overlap + ") 必须小于 chunkSize(" + chunkSize + ")");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public String name() {
        return "recursive-character";
    }

    @Override
    public String describe() {
        return "递归字符 chunk=" + chunkSize + " overlap=" + overlap;
    }

    @Override
    public List<Chunk> chunk(Document document) {
        List<String> pieces = split(document.content(), 0);
        return document.toChunks(mergeWithOverlap(pieces));
    }

    /**
     * 递归切分：尝试用第 {@code separatorIndex} 级分隔符切开，过长的部分交给下一级。
     *
     * @param text           待切分文本
     * @param separatorIndex 当前使用的分隔符层级
     * @return 不超过 {@code chunkSize} 的文本片段列表
     */
    private List<String> split(String text, int separatorIndex) {
        if (text.length() <= chunkSize) {
            return List.of(text);
        }
        if (separatorIndex >= SEPARATORS.size()) {
            return List.of(text);
        }

        String separator = SEPARATORS.get(separatorIndex);

        // 空分隔符表示已无更细粒度可切，直接按窗口硬切，避免无限递归。
        if (separator.isEmpty()) {
            return hardSplit(text);
        }

        List<String> parts = splitKeepingSeparator(text, separator);

        // 该分隔符在文本中不存在或未能真正切开，尝试下一级
        if (parts.size() <= 1) {
            return split(text, separatorIndex + 1);
        }

        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.length() > chunkSize) {
                result.addAll(split(part, separatorIndex + 1));
            } else if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result.isEmpty() ? List.of(text) : result;
    }

    /**
     * 按分隔符切分，并把分隔符**保留在前一段末尾**。
     *
     * <p>保留分隔符是刻意的：句末标点是语义信息，丢弃后片段读起来会缺一个结尾，
     * 也会让"这句话是否完整"无法判断。
     */
    private List<String> splitKeepingSeparator(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = text.indexOf(separator, start)) >= 0) {
            int end = idx + separator.length();
            parts.add(text.substring(start, end));
            start = end;
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    /**
     * 兜底硬切：无任何可用分隔符时按 chunkSize 均分。
     */
    private List<String> hardSplit(String text) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            parts.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return parts;
    }

    /**
     * 合并相邻小片段，并注入窗口重叠。
     *
     * <p>递归切分会产出大量短片段（如单独的一行标题），直接入库会导致
     * 检索质量下降（一个短片段携带的信息太少）。这里先把小片段合并到接近
     * {@code chunkSize}，再在前一片段的尾部取 {@code overlap} 个字符作为下一片段的开头。
     */
    private List<String> mergeWithOverlap(List<String> pieces) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String piece : pieces) {
            // 单个片段本身就超长（递归已尽力降级）时单独成块。
            // 关键：必须先把已累积的 buffer 落盘，否则超长片段会被插到
            // 它前面那些片段的前面，破坏片段与原文的顺序一致性。
            if (piece.length() > chunkSize) {
                if (current.length() > 0) {
                    merged.add(current.toString());
                    current.setLength(0);
                }
                merged.add(piece);
                continue;
            }

            if (current.length() + piece.length() > chunkSize && current.length() > 0) {
                merged.add(current.toString());
                current.setLength(0);
            }
            current.append(piece);
        }
        if (current.length() > 0) {
            merged.add(current.toString());
        }

        return applyOverlap(merged);
    }

    /**
     * 把前一块的尾部 {@code overlap} 个字符前置到后一块，形成重叠。
     */
    private List<String> applyOverlap(List<String> blocks) {
        if (overlap == 0 || blocks.size() <= 1) {
            return blocks;
        }
        List<String> result = new ArrayList<>(blocks.size());
        result.add(blocks.get(0));
        for (int i = 1; i < blocks.size(); i++) {
            String previous = blocks.get(i - 1);
            String prefix = previous.substring(Math.max(0, previous.length() - overlap));
            result.add(prefix + blocks.get(i));
        }
        return result;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public int overlap() {
        return overlap;
    }
}
