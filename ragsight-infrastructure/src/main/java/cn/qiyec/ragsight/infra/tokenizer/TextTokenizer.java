package cn.qiyec.ragsight.infra.tokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 切词工具：中文按单字、英文/数字按连续片段。
 *
 * <p><b>为什么单独抽成工具类而不是各自实现</b>：
 * 离线向量化（{@code DeterministicHashEmbedding}）与 BM25 都必须使用
 * **完全相同的切词规则**。若两处各写一套，同一份语料在向量路与关键词路
 * 会被切成不同词元，混合检索的效果就无法归因——你无法判断差异来自
 * 融合策略还是来自切词不一致。把规则收敛到一处，是保证实验可比的前提。
 *
 * <p><b>为什么中文按单字而不是引入分词库</b>：引入分词库会给
 * infrastructure 增加较重依赖，且分词器本身应当是一个可对比的实验变量
 * （见 ADR-004）。按单字切分配合倒排与向量检索，对中文字面重合场景已经有效。
 * 将来若引入专业分词器，只需替换 {@code Tokenizer} 端口的实现。
 */
public final class TextTokenizer {

    private TextTokenizer() {
    }

    /**
     * 把文本切分为词元。
     *
     * <p>规则：
     * <ul>
     *   <li>汉字（CJK 基本区与扩展 A 区）按单字切分</li>
     *   <li>字母与数字按连续片段切分，并转为小写（使 "Pool" 与 "pool" 可匹配）</li>
     *   <li>标点、空白一律丢弃</li>
     * </ul>
     *
     * @param text 待切分文本；null 或空串返回空列表
     * @return 词元列表，按出现顺序
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushLatin(latin, tokens);
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(latin, tokens);
            }
        }
        flushLatin(latin, tokens);
        return tokens;
    }

    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    /**
     * 是否为中日韩表意文字。
     *
     * <p>范围：CJK 统一表意文字（U+4E00–U+9FFF）与扩展 A 区（U+3400–U+4DBF）。
     */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF);
    }
}
