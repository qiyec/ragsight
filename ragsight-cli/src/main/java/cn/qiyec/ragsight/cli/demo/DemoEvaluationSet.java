package cn.qiyec.ragsight.cli.demo;

import cn.qiyec.ragsight.domain.evaluation.EvaluationSet;
import cn.qiyec.ragsight.domain.evaluation.GoldenCase;
import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 演示评测集——故意设计成能暴露不同检索器弱点的用例集合。
 *
 * <p><b>标注方式：按文本内容定位片段，而非硬编码片段 id</b>。
 * 片段 id 形如 {@code billing-error#2}，其中的序号取决于切分策略与参数。
 * 一旦调整切分，硬编码的 id 会全部失效，评测集就悄悄变成错的
 * （指向不存在的片段 → Recall 恒为 0，而你可能以为是检索出了问题）。
 * 改为"按特征文本定位"，评测集就能在切分变化后自动跟随。
 *
 * <p><b>用例为何这样设计</b>：诊断工具的价值在于区分不同的失败模式，
 * 因此评测集必须包含能区分它们的问题：
 * <ul>
 *   <li>精确编号类（错误码）—— 向量路弱、关键词路强</li>
 *   <li>同义改写类 —— 关键词路弱、向量路强</li>
 *   <li>负样本 —— 检验是否会硬凑答案</li>
 * </ul>
 * 如果评测集全是"和文档用词一致的问题"，两条召回路径不会有任何差异，
 * 诊断报告也就无从给出建议。
 */
public final class DemoEvaluationSet {

    private DemoEvaluationSet() {
    }

    /**
     * 构造演示评测集。
     *
     * @param corpus 已切分的语料，用于按特征文本定位片段 id
     * @return 评测集
     */
    public static EvaluationSet build(List<Chunk> corpus) {
        List<GoldenCase> cases = new ArrayList<>();

        // ---- 精确编号类：向量路容易漏，关键词路应命中 ----
        // 标注特征文本刻意取短。长片段容易因换行、空格、标点差异而失配——
        // 本次就踩到这个坑：语料写在 Java 文本块里，行尾空格会被剔除，
        // 于是「90% 时」与实际的「90%时」不匹配。短特征文本对格式变化更稳健。
        cases.add(caseOf("q-e4021", "错误码 E4021 是什么意思", corpus,
                "E4021 表示"));
        cases.add(caseOf("q-e1002", "E1002 错误怎么处理", corpus,
                "E1002 表示"));
        cases.add(caseOf("q-e4023", "错误码 E4023 代表什么", corpus,
                "E4023 表示"));

        // ---- 同义改写类：问题用词与文档不完全一致 ----
        cases.add(caseOf("q-settle", "账单什么时候出", corpus,
                "次月 3 日前生成"));
        cases.add(caseOf("q-lock", "密码输错太多次会怎样", corpus,
                "锁定账号 30 分钟"));
        cases.add(caseOf("q-quota-warn", "存储快满的时候有提醒吗", corpus,
                "达到配额的"));
        cases.add(caseOf("q-outbound", "流量怎么收费", corpus,
                "出站流量计费"));

        // ---- 负样本：知识库中不存在答案的问题 ----
        // 检验检索器是否会"硬凑"一个最相似片段当作答案
        cases.add(GoldenCase.of("neg-mobile-app", "手机客户端支持指纹登录吗", List.of()));
        cases.add(GoldenCase.of("neg-refund", "如何申请退款", List.of()));

        return EvaluationSet.of("演示评测集（7 个正样本 + 2 个负样本）", cases);
    }

    /**
     * 按特征文本定位片段，构造一个用例。
     *
     * @throws IllegalStateException 找不到匹配片段时——这通常意味着
     *                               特征文本已与语料不同步，必须立即暴露而非静默跳过
     */
    private static GoldenCase caseOf(String id, String question, List<Chunk> corpus,
                                     String marker) {
        Set<ChunkId> expected = locate(corpus, marker);
        if (expected.isEmpty()) {
            throw new IllegalStateException(
                    "评测集标注失效：语料中找不到包含「" + marker + "」的片段。"
                            + "这通常意味着语料或切分参数已变更，请同步更新评测集。");
        }
        return GoldenCase.of(id, question, List.copyOf(expected));
    }

    /**
     * 找到所有包含特征文本的片段。
     *
     * <p>返回**全部**匹配片段而非仅第一个：同一事实可能因切分重叠
     * 出现在多个片段中，把它们都标为期望可以避免"召回了另一个正确片段
     * 却判定为未命中"的假阴性。
     */
    private static Set<ChunkId> locate(List<Chunk> corpus, String marker) {
        Set<ChunkId> matched = new LinkedHashSet<>();
        for (Chunk chunk : corpus) {
            if (chunk.text().contains(marker)) {
                matched.add(chunk.id());
            }
        }
        return matched;
    }

    /** 报告标题用的评测集说明。 */
    public static String description() {
        return "7 个正样本（含精确编号与同义改写两类）+ 2 个负样本";
    }

    /**
     * 供报告使用：取某用例期望片段的摘要文本。
     */
    public static Optional<String> expectedSummary(List<Chunk> corpus, ChunkId id) {
        return corpus.stream()
                .filter(c -> c.id().equals(id))
                .map(c -> abbreviate(c.text(), 40))
                .findFirst();
    }

    private static String abbreviate(String text, int maxLength) {
        String compact = text.replaceAll("\\s+", " ").strip();
        return compact.length() <= maxLength
                ? compact
                : compact.substring(0, maxLength) + "…";
    }
}
