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
        // 去重：单变量对比会同时传"改之前"和"改之后"。改的是切分时，
        // 两者的召回方式是同一个；改的是召回时，两者的切分是同一个。
        // 所以两边都要去重，让这一批变成干净的
        // "2 个切分 × 1 种召回" 或 "1 个切分 × 2 种召回"。
        //
        // 去重之后如果只剩一个组合，说明这一改什么都没变——
        // 但不在这里报错，因为调用方更清楚该怎么措辞（见 compare_one_change。
        // 那里的校验会给出"改成和现在一样的值"这类能看懂的话）。
        List<ExperimentPlan> uniquePlans = distinctPlansById(plans);
        List<RetrieverConfig> uniqueRetrievers = distinctById(retrievers);

        if (uniquePlans.size() * uniqueRetrievers.size() < 2) {
            return List.of();
        }

        DiagnosisService.RunOutcome outcome = service.diagnosePlans(
                corpusId, uniquePlans, uniqueRetrievers, embeddingSource);

        DiagnosisReport report = outcome.report();
        allFindings.addAll(report.findings());
        experimentCount += report.experimentCount();

        List<Attempted> results = new ArrayList<>();
        for (ExperimentResult result : report.results().values()) {
            results.add(record(result));
        }
        return List.copyOf(results);
    }

    /**
     * 只跑，不碰"当前最好"。
     *
     * <p>单变量对比要用这个。因为对比的一批里既有"改之前"也有"改之后"，
     * 如果顺手把它们都拿去竞争"当前最好"，就会出现：
     * 改之后变差了，而"改之前"因为还留在批里又被选回来——
     * 表现上像是改动没生效，实际是状态没更新对。
     *
     * <p>跑完由调用方决定要不要用结果更新最好，见 {@link #applyIfBetter}。
     */
    List<Attempted> runForComparison(List<ExperimentPlan> plans,
                                     List<RetrieverConfig> retrievers) {
        return run(plans, retrievers);
    }

    /**
     * 跑一批并让结果参与"当前最好"的竞争。
     *
     * <p>只适合"这一批全是要试的新方案"的场景（比如跑基准）。
     * 对比场景请用 {@link #runForComparison} + {@link #applyIfBetter}。
     */
    List<Attempted> runAndUpdateBest(List<ExperimentPlan> plans,
                                     List<RetrieverConfig> retrievers) {
        List<Attempted> results = run(plans, retrievers);
        for (Attempted attempt : results) {
            applyIfBetter(attempt);
        }
        return results;
    }

    /**
     * 这个方案比当前最好更好时，把它设为当前最好。
     *
     * <p>多轮探索里"当前最好"是逐步爬上去的，所以只是比较，
     * 不是无条件替换。
     */
    void applyIfBetter(Attempted attempt) {
        if (best == null || attempt.mrr() > best.mrr()) {
            best = attempt;
        }
    }

    /** 把一个实验记录进笔记本。 */
    private Attempted record(ExperimentResult result) {
        Attempted attempt = new Attempted(
                result.plan().id(),
                result.plan().displayName(),
                result.retriever().id(),
                result.retriever().displayName(),
                result.score(recallName()),
                result.score(mrrName()),
                result.score(ndcgName()));
        attempted.put(attempt.key(), attempt);
        return attempt;
    }

    /** 按 id 去重，保持原有顺序。 */
    private static List<RetrieverConfig> distinctById(List<RetrieverConfig> retrievers) {
        Map<String, RetrieverConfig> byId = new LinkedHashMap<>();
        for (RetrieverConfig config : retrievers) {
            byId.putIfAbsent(config.id(), config);
        }
        return List.copyOf(byId.values());
    }

    /**
     * 切分方案也按 id 去重。
     *
     * <p>为什么需要：单变量对比要同时传"改之前"和"改之后"。
     * 如果两者恰好是同一个方案（比如想把某个参数改成它已经是的值），
     * 传两个同 id 的进去会让报告校验直接拦下——
     * 那个校验拦得对，报告里两行一样的分不清哪个是哪个。
     * 但更好的做法是在这里就去掉重复，让调用方看到一句能看懂的话。
     */
    private static List<ExperimentPlan> distinctPlansById(List<ExperimentPlan> plans) {
        Map<String, ExperimentPlan> byId = new LinkedHashMap<>();
        for (ExperimentPlan plan : plans) {
            byId.putIfAbsent(plan.id(), plan);
        }
        return List.copyOf(byId.values());
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
