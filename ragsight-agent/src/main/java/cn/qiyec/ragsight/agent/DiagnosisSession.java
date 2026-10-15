package cn.qiyec.ragsight.agent;

import cn.qiyec.ragsight.application.DiagnosisReport;
import cn.qiyec.ragsight.application.ExperimentPlan;
import cn.qiyec.ragsight.application.ExperimentResult;
import cn.qiyec.ragsight.application.RetrieverConfig;
import cn.qiyec.ragsight.diagnosis.DiagnosisService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次诊断探索的"笔记本"。
 *
 * <p>诊断 Agent 不是一次性跑完全部组合，而是**一次改一个东西、看结果、
 * 再决定下一个改什么**。所以要有个地方记住：基准是什么、试过哪些、
 * 各自结果如何、当前最好的是哪个。
 *
 * <p><b>为什么要"当前最好"这个概念</b>：Agent 每次比较都应该拿
 * 目前最好的配置当基准，而不是永远跟最开始的比。否则它会反复
 * 在已经否掉的方案上打转。
 *
 * <p>这个类是 Agent 的状态，不参与结论推导——所有结论仍然是
 * {@code DiagnosisEngine} 用固定规则算出来的。
 */
final class DiagnosisSession {

    private final String corpusId;
    private final String corpusLabel;
    private final int topK;
    private final int chunkOverlap;
    private final DiagnosisService service;
    private final DiagnosisService.ApiRequest embeddingSource;

    /** 跑过的每个方案，按显示名去重。值是它在报告里的指标。 */
    private final Map<String, Attempted> attempted = new LinkedHashMap<>();

    /** 当前最好的方案。第一次比较之后才有值。 */
    private Attempted best;

    /** 所有跑出来的结论，最后汇总给用户。 */
    private final List<String> allFindings = new ArrayList<>();

    /** 一共跑过几次实验（算上每组里被对比的两个方案）。 */
    private int experimentCount;

    DiagnosisSession(String corpusId,
                     String corpusLabel,
                     int topK,
                     int chunkOverlap,
                     DiagnosisService service,
                     DiagnosisService.ApiRequest embeddingSource) {
        this.corpusId = corpusId;
        this.corpusLabel = corpusLabel;
        this.topK = topK;
        this.chunkOverlap = chunkOverlap;
        this.service = service;
        this.embeddingSource = embeddingSource;
    }

    String corpusId() {
        return corpusId;
    }

    String corpusLabel() {
        return corpusLabel;
    }

    int topK() {
        return topK;
    }

    int chunkOverlap() {
        return chunkOverlap;
    }

    /**
     * 跑一组方案，把结果记下来。
     *
     * <p>同一批里的所有方案用同一份语料、同一个 topK，
     * 所以它们的指标可以直接比——这是能归因的前提。
     */
    List<Attempted> run(List<ExperimentPlan> plans, List<RetrieverConfig> retrievers) {
        DiagnosisService.RunOutcome outcome =
                service.diagnosePlans(corpusId, plans, retrievers, embeddingSource);

        DiagnosisReport report = outcome.report();
        allFindings.addAll(report.findings());
        experimentCount += report.experimentCount();

        List<Attempted> results = new ArrayList<>();
        for (ExperimentResult result : report.results().values()) {
            Attempted attempt = new Attempted(
                    result.plan().id(),
                    result.plan().displayName(),
                    result.retriever().id(),
                    result.retriever().displayName(),
                    result.score(recallName()),
                    result.score(mrrName()),
                    result.score(ndcgName()));
            attempted.put(attempt.key(), attempt);
            results.add(attempt);
        }

        // 如果还没定基准，就把这一批里 MRR 最高的当作当前最好
        for (Attempted attempt : results) {
            if (best == null || attempt.mrr() > best.mrr()) {
                best = attempt;
            }
        }
        return List.copyOf(results);
    }

    /** 已经跑过的方案。 */
    List<Attempted> attempted() {
        return List.copyOf(attempted.values());
    }

    /** 当前最好的方案；一次都没跑过时为 null。 */
    Attempted best() {
        return best;
    }

    /** 一共跑过多少次实验。 */
    int experimentCount() {
        return experimentCount;
    }

    /** 所有结论文案。 */
    List<String> allFindings() {
        return List.copyOf(allFindings);
    }

    String recallName() {
        return "Recall@" + topK;
    }

    String mrrName() {
        return "MRR@" + topK;
    }

    String ndcgName() {
        return "nDCG@" + topK;
    }

    /**
     * 已经试过哪些"切分窗口"取值。
     *
     * <p>给 Agent 看的，免得它重复试同一个数。
     */
    List<Integer> triedChunkSizes() {
        return attempted.values().stream()
                .map(a -> parseChunkSize(a.planLabel()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** 已经试过哪些召回方式。 */
    List<String> triedRetrievers() {
        return attempted.values().stream()
                .map(Attempted::retrieverLabel)
                .distinct()
                .toList();
    }

    /** 有没有试过带重排的方案。 */
    boolean triedRerank() {
        return attempted.values().stream().anyMatch(a -> a.planLabel().contains("重排"));
    }

    private Integer parseChunkSize(String planLabel) {
        var m = java.util.regex.Pattern.compile("(\\d+)\\s*字").matcher(planLabel);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** 一个跑过的方案及其指标。 */
    record Attempted(String planId,
                     String planLabel,
                     String retrieverId,
                     String retrieverLabel,
                     double recall,
                     double mrr,
                     double ndcg) {

        String key() {
            return planId + "|" + retrieverId;
        }

        String displayName() {
            return planLabel + " · " + retrieverLabel;
        }
    }
}
