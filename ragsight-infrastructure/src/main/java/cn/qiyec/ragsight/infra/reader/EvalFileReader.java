package cn.qiyec.ragsight.infra.reader;

import cn.qiyec.ragsight.application.GoldenCaseSetSpec;
import cn.qiyec.ragsight.application.GoldenCaseSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读测试集文件。
 *
 * <p>文件格式（刻意做成纯文本，方便手写和进版本库）：
 *
 * <pre>
 *   Q: 用户问题
 *   E: 期望召回的片段里应当出现的一段文字
 *   E: 再来一条
 *
 *   Q: 下一个问题
 *   E: ...
 * </pre>
 *
 * <p>规则：
 * <ul>
 *   <li>一个用例一个块，块之间用空行或 {@code ---} 分隔</li>
 *   <li>{@code Q:} 是问题，一个块里只能有一条</li>
 *   <li>{@code E:} 是期望文字，可以有多条；一条都不写的块是负样本</li>
 *   <li>{@code #} 开头的行是注释，会被忽略</li>
 *   <li>用例 id 自动生成（{@code c001}、{@code c002}...），不用手写</li>
 * </ul>
 *
 * <p><b>为什么不手写 id</b>：手写 id 会带来两类麻烦——忘了写、以及写重复。
 * 自动生成既省事又保证唯一。报告里要按 id 定位时，顺序也是稳定的。
 *
 * <p><b>为什么不报行号</b>：报行号能让用户更快找到写错的地方，
 * 但也会让错误信息变长。这里选择报"第几个用例"，通常已经够定位了。
 */
public final class EvalFileReader {

    private static final String QUESTION_PREFIX = "Q:";
    private static final String EVIDENCE_PREFIX = "E:";
    private static final String COMMENT_PREFIX = "#";

    private EvalFileReader() {
    }

    /**
     * 从文件读测试集描述。
     *
     * @param file 测试集文件
     * @return 测试集描述（还没和语料片段对应上）
     * @throws UncheckedIOException 读文件失败
     * @throws IllegalArgumentException 内容不合法
     */
    public static GoldenCaseSetSpec read(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("测试集文件不存在：" + file.toAbsolutePath());
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("这不是一个文件：" + file.toAbsolutePath());
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return parse(text, fileNameWithoutExtension(file));
        } catch (IOException e) {
            throw new UncheckedIOException("读测试集文件失败：" + file, e);
        }
    }

    /**
     * 解析测试集内容。
     *
     * <p>拆成单独方法是为了能直接对字符串做测试，不用每次建临时文件。
     */
    static GoldenCaseSetSpec parse(String text, String name) {
        if (text == null) {
            throw new IllegalArgumentException("内容不能为空");
        }

        List<GoldenCaseSpec> cases = new ArrayList<>();
        String currentQuestion = null;
        List<String> currentMarkers = new ArrayList<>();
        int caseNumber = 0;

        for (String rawLine : text.split("\n", -1)) {
            String line = stripBom(rawLine).strip();

            // 注释和空行都可能意味着"这一块结束了"
            if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) {
                if (currentQuestion != null) {
                    caseNumber++;
                    cases.add(new GoldenCaseSpec(
                            String.format("c%03d", caseNumber),
                            currentQuestion,
                            List.copyOf(currentMarkers)));
                    currentQuestion = null;
                    currentMarkers = new ArrayList<>();
                }
                continue;
            }

            // 分隔线：显式结束当前块
            if (line.equals("---")) {
                if (currentQuestion != null) {
                    caseNumber++;
                    cases.add(new GoldenCaseSpec(
                            String.format("c%03d", caseNumber),
                            currentQuestion,
                            List.copyOf(currentMarkers)));
                    currentQuestion = null;
                    currentMarkers = new ArrayList<>();
                }
                continue;
            }

            if (line.startsWith(QUESTION_PREFIX)) {
                // 上一个块还没收尾就来了新问题，说明用户忘了空行。
                // 这里宽容处理：先把上一块收掉，而不是报错中断。
                if (currentQuestion != null) {
                    caseNumber++;
                    cases.add(new GoldenCaseSpec(
                            String.format("c%03d", caseNumber),
                            currentQuestion,
                            List.copyOf(currentMarkers)));
                    currentMarkers = new ArrayList<>();
                }
                String question = line.substring(QUESTION_PREFIX.length()).strip();
                if (question.isEmpty()) {
                    throw new IllegalArgumentException(
                            "第 " + (caseNumber + 1) + " 个用例的问题是空的（Q: 后面没写东西）");
                }
                currentQuestion = question;
                continue;
            }

            if (line.startsWith(EVIDENCE_PREFIX)) {
                if (currentQuestion == null) {
                    throw new IllegalArgumentException(
                            "有一行 E: 出现在任何 Q: 之前。"
                                    + "每个 E: 都必须写在某个 Q: 下面。内容：" + line);
                }
                String marker = line.substring(EVIDENCE_PREFIX.length()).strip();
                if (marker.isEmpty()) {
                    throw new IllegalArgumentException(
                            "有用例的 E: 后面没写东西（第 " + (caseNumber + 1) + " 个用例附近）");
                }
                currentMarkers.add(marker);
                continue;
            }

            throw new IllegalArgumentException(
                    "看不懂这一行（每行都要以 Q:、E: 或 # 开头）：" + line);
        }

        // 收尾最后一个块
        if (currentQuestion != null) {
            caseNumber++;
            cases.add(new GoldenCaseSpec(
                    String.format("c%03d", caseNumber),
                    currentQuestion,
                    List.copyOf(currentMarkers)));
        }

        if (cases.isEmpty()) {
            throw new IllegalArgumentException(
                    "测试集文件里没有找到任何用例。每个用例至少要有两行：\n"
                            + "  Q: 你的问题\n"
                            + "  E: 期望召回的片段里会出现的文字");
        }

        return new GoldenCaseSetSpec(name, cases);
    }

    /** 去掉 UTF-8 BOM，否则第一行的 "Q:" 会匹配不上。 */
    private static String stripBom(String line) {
        return line.isEmpty() || line.charAt(0) != '\uFEFF' ? line : line.substring(1);
    }

    private static String fileNameWithoutExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
