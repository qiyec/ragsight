package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.knowledge.Document;

import java.util.List;

/**
 * 一次诊断的输入。
 *
 * <p>装三样东西：<b>文档</b>（测哪个知识库）、<b>测试集</b>（用什么尺子）、
 * <b>要对比的方案</b>（切分方案有哪些、召回方案有哪些）。
 *
 * <p><b>为什么传文档而不是传切好的片段</b>：
 * 如果传的是切好的片段，就意味着切分已经在外面做完了，
 * 那"对比不同切分"就没法做了——一个请求只能有一种切分结果。
 * 所以这里传原始文档，由诊断过程按各个切分方案分别切一遍。
 *
 * <p>测试集也用"还没落到片段上"的形式（{@link GoldenCaseSetSpec}）：
 * 期望文字要在每个切分方案下重新找一遍对应片段，
 * 因为不同切分切出来的片段不一样。
 *
 * @param corpusName 语料名称，出现在报告标题里
 * @param documents  原始文档
 * @param caseSpec   测试集描述（还没对应到片段）
 * @param chunkings  要对比的切分方案，至少一个
 * @param retrievers 要对比的召回方案，至少一个
 */
public record DiagnosisRequest(
        String corpusName,
        List<Document> documents,
        GoldenCaseSetSpec caseSpec,
        List<ExperimentPlan> chunkings,
        List<RetrieverConfig> retrievers) {

    public DiagnosisRequest {
        if (corpusName == null || corpusName.isBlank()) {
            throw new IllegalArgumentException("语料名称不能为空");
        }
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("文档不能为空");
        }
        if (caseSpec == null) {
            throw new IllegalArgumentException("测试集不能为空");
        }
        if (chunkings == null || chunkings.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个切分方案");
        }
        if (retrievers == null || retrievers.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个召回方案");
        }
        documents = List.copyOf(documents);
        chunkings = List.copyOf(chunkings);
        retrievers = List.copyOf(retrievers);

        // id 重复会让报告的两行长得一样，分不清哪个是哪个的结果
        requireUniqueIds(chunkings.stream().map(ExperimentPlan::id).toList(), "切分方案");
        requireUniqueIds(retrievers.stream().map(RetrieverConfig::id).toList(), "召回方案");
    }

    private static void requireUniqueIds(List<String> ids, String what) {
        List<String> duplicated = ids.stream()
                .filter(id -> ids.indexOf(id) != ids.lastIndexOf(id))
                .distinct()
                .toList();
        if (!duplicated.isEmpty()) {
            throw new IllegalArgumentException(
                    what + "的 id 重复了，报告没法区分哪一行是哪个：" + duplicated);
        }
    }

    /** 文档数量。 */
    public int documentCount() {
        return documents.size();
    }

    /** 要跑多少次实验：切分数 × 召回数。 */
    public int experimentCount() {
        return chunkings.size() * retrievers.size();
    }

    /** 实验次数是否偏多。用于提醒用户可能要等一会儿。 */
    public boolean isLargeRun() {
        return experimentCount() > 9;
    }
}
