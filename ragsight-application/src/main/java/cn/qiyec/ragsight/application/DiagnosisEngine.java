package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.evaluation.EvaluationSet;
import cn.qiyec.ragsight.domain.evaluation.GoldenCase;
import cn.qiyec.ragsight.domain.evaluation.MRR;
import cn.qiyec.ragsight.domain.evaluation.Metric;
import cn.qiyec.ragsight.domain.evaluation.NDCG;
import cn.qiyec.ragsight.domain.evaluation.RecallAtK;
import cn.qiyec.ragsight.domain.evaluation.RetrievedEvidence;
import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.retrieval.Retriever;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断引擎（用例编排）——本工具的核心用例。
 *
 * <p>流程：对每一种检索配置跑完整评测集 → 计算指标 → 用确定性规则推导结论。
 *
 * <p><b>本类只做编排，不含算法</b>：
 * 检索由 {@link Retriever} 完成，指标由 {@link Metric} 计算，
 * 构建检索器由 {@link RetrieverFactory} 负责（基础设施实现）。
 * 这样应用层不依赖任何具体技术（铁律 R2），且全部逻辑可被单测覆盖。
 *
 * <p><b>结论由规则推导，不调 LLM</b>：
 * 同一份输入必须得到同一份报告（铁律 R5）。判断必须来自数字。
 */
public final class DiagnosisEngine {

    /** 视为"明显提升"的阈值。低于此值的差异不足以支撑配置变更建议。 */
    private static final double NOTABLE_DELTA = 0.05;

    /** MRR 低于此值视为排序质量不足，暗示需要重排。 */
    private static final double WEAK_RANKING_THRESHOLD = 0.5;

    /** Recall 达到此值视为召回已基本充分。 */
    private static final double SUFFICIENT_RECALL = 0.8;

    private final RetrieverFactory factory;

    public DiagnosisEngine(RetrieverFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory 不能为空");
        }
        this.factory = factory;
    }

    /**
     * 执行诊断。
     *
     * <p>基准配置取**第一个配置**。这是刻意的约定：
     * 调用方应把"当前线上使用的方案"放在第一位，
     * 这样报告中所有差值都是"相对现状"的，最符合用户的心智模型。
     *
     * @param request 诊断请求
     * @return 诊断报告
     */
    public DiagnosisReport diagnose(DiagnosisRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }

        List<RetrieverConfig> configs = request.configs();
        Map<String, ExperimentResult> results = new LinkedHashMap<>();

        for (RetrieverConfig config : configs) {
            results.put(config.id(), runExperiment(config, request.evalSet(), config.topK()));
        }

        String baselineId = configs.get(0).id();

        List<String> findings = deriveFindings(request, results, baselineId);

        List<String> caseIds = request.evalSet().cases().stream()
                .map(GoldenCase::id)
                .toList();

        return new DiagnosisReport(
                request.corpusName(),
                request.evalSet().size(),
                request.evalSet().negativeCount(),
                caseIds,
                baselineId,
                results,
                findings);
    }

    /**
     * 跑一次实验：对评测集每个用例执行检索，再计算各指标。
     */
    private ExperimentResult runExperiment(RetrieverConfig config,
                                           EvaluationSet evalSet,
                                           int topK) {
        Retriever retriever = factory.create(config);

        List<CaseOutcome> outcomes = new ArrayList<>(evalSet.size());
        // 逐用例的召回结果按评测集顺序暂存，用于整体指标计算
        List<RetrievedEvidence> evidences = new ArrayList<>(evalSet.size());

        for (GoldenCase testCase : evalSet.cases()) {
            List<ScoredChunk> hits = retriever.retrieve(testCase.question(), topK);
            List<ChunkId> rankedIds = hits.stream().map(ScoredChunk::chunkId).toList();
            RetrievedEvidence evidence = RetrievedEvidence.of(rankedIds);

            evidences.add(evidence);
            outcomes.add(CaseOutcome.of(testCase, evidence));
        }

        List<Metric> metrics = List.of(new RecallAtK(topK), new MRR(topK), new NDCG(topK));
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Metric metric : metrics) {
            scores.put(metric.name(), metric.scoreAll(evalSet, evidences));
        }

        return new ExperimentResult(config, scores, outcomes);
    }

    /**
     * 用确定性规则从指标推导结论。
     *
     * <p>规则刻意保持简单可解释——用户需要理解"为什么得出这个结论"，
     * 因此每条结论都必须能对应到报告里的具体数字。
     */
    private List<String> deriveFindings(DiagnosisRequest request,
                                        Map<String, ExperimentResult> results,
                                        String baselineId) {
        List<String> findings = new ArrayList<>();
        ExperimentResult baseline = results.get(baselineId);
        int topK = request.configs().get(0).topK();

        String recallName = "Recall@" + topK;
        String mrrName = "MRR@" + topK;

        findings.add(String.format(
                "语料 %d 个片段，评测集 %d 个用例（其中负样本 %d 个）",
                request.corpusSize(), request.evalSet().size(),
                request.evalSet().negativeCount()));

        if (request.evalSet().negativeCount() == 0) {
            findings.add("⚠ 评测集不含负样本。检索器可能通过「总是返回最相似内容」取得虚高分数，"
                    + "建议补充「应当答不出来」的问题。");
        }

        double baselineRecall = baseline.score(recallName);
        double baselineMrr = baseline.score(mrrName);

        // 规则①：召回是否不足
        if (baselineRecall < SUFFICIENT_RECALL) {
            findings.add(String.format(
                    "基准（%s）%s = %.3f，召回不足：约 %.0f%% 的期望证据未能进入候选池。"
                            + "此时无论排序如何优化都无法提升上限——应优先检查切分粒度、"
                            + "embedding 模型适配性与是否需要关键词召回。",
                    baseline.config().displayName(), recallName, baselineRecall,
                    (1 - baselineRecall) * 100));
        } else {
            findings.add(String.format(
                    "基准（%s）%s = %.3f，召回基本充分，瓶颈更可能在排序环节。",
                    baseline.config().displayName(), recallName, baselineRecall));
        }

        // 规则②：排序质量是否不足（且召回已够）
        if (baselineRecall >= SUFFICIENT_RECALL && baselineMrr < WEAK_RANKING_THRESHOLD) {
            findings.add(String.format(
                    "基准 %s = %.3f 偏低，但召回充分——这是「排序问题」而非「召回问题」的典型特征。"
                            + "下一步应尝试重排（Cross-Encoder），而不是继续调切分。",
                    mrrName, baselineMrr));
        }

        // 规则③：对比其他配置相对基准的收益
        Map<String, Map<String, Double>> deltas = new LinkedHashMap<>();
        for (Map.Entry<String, ExperimentResult> entry : results.entrySet()) {
            if (entry.getKey().equals(baselineId)) {
                continue;
            }
            deltas.put(entry.getKey(), entry.getValue().deltasAgainst(baseline));
        }

        String bestRecallConfigId = null;
        double bestRecallDelta = 0;
        for (Map.Entry<String, Map<String, Double>> entry : deltas.entrySet()) {
            Double recallDelta = entry.getValue().get(recallName);
            if (recallDelta != null && recallDelta > bestRecallDelta) {
                bestRecallDelta = recallDelta;
                bestRecallConfigId = entry.getKey();
            }
        }

        if (bestRecallConfigId != null && bestRecallDelta >= NOTABLE_DELTA) {
            ExperimentResult best = results.get(bestRecallConfigId);
            findings.add(String.format(
                    "✅ 换成「%s」可把 %s 提升 %.3f（%.3f → %.3f）。这是本次诊断中收益最大的单项改动。",
                    best.config().displayName(), recallName, bestRecallDelta,
                    baselineRecall, best.score(recallName)));
        } else if (bestRecallConfigId != null) {
            ExperimentResult best = results.get(bestRecallConfigId);
            findings.add(String.format(
                    "「%s」相对基准的 %s 仅提升 %.3f，未达到显著阈值（%.2f）——"
                            + "不值得为此增加系统复杂度。",
                    best.config().displayName(), recallName, bestRecallDelta, NOTABLE_DELTA));
        }

        // 规则④：明确指出哪些改动不值得做（与"值得做"同样有价值）
        List<String> notWorth = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> entry : deltas.entrySet()) {
            Double recallDelta = entry.getValue().get(recallName);
            Double mrrDelta = entry.getValue().get(mrrName);
            boolean recallFlat = recallDelta == null || Math.abs(recallDelta) < NOTABLE_DELTA;
            boolean mrrFlat = mrrDelta == null || mrrDelta < NOTABLE_DELTA;
            if (recallFlat && mrrFlat) {
                notWorth.add(results.get(entry.getKey()).config().displayName());
            }
        }
        if (!notWorth.isEmpty()) {
            findings.add("以下配置相对基准无可观测收益，建议不要采用：" + String.join("、", notWorth));
        }

        return List.copyOf(findings);
    }
}
