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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 诊断引擎——这个工具的核心用例。
 *
 * <p>干的事：把每种"切分方案 × 召回方案"都跑一遍，算出指标，再用固定规则推导结论。
 *
 * <p><b>为什么切分要在这一层做</b>：诊断要回答"切分该不该调"，
 * 就必须对同一批文档用不同方式各切一遍，分别跑指标。如果切分在外面做完
 * 再传进来，一个请求就只能有一种切分结果，这个问题就答不了了。
 *
 * <p><b>这个类只负责把几步串起来，不含算法</b>：
 * 召回交给 {@link Retriever}，指标交给 {@link Metric}，
 * 建检索器交给 {@link RetrieverFactory}。所以它不依赖任何具体技术。
 *
 * <p><b>结论由固定规则推导，不调模型</b>：
 * 同一份输入必须得到同一份报告。判断必须来自数字。
 */
public final class DiagnosisEngine {

    /** 提升小于这个值，就认为"不值得为此增加复杂度"。 */
    private static final double NOTABLE_DELTA = 0.05;

    /** MRR 低于这个值，认为排序质量不够，指向"需要重排"。 */
    private static final double WEAK_RANKING_THRESHOLD = 0.5;

    /** Recall 达到这个值，认为召回已经够用。 */
    private static final double SUFFICIENT_RECALL = 0.8;

    /**
     * 按语料造检索器工厂。
     *
     * <p>之所以要"按语料现造"：每个切分方案切出来的片段都不一样，
     * 检索器必须基于对应的那批片段来建。共用一个工厂会让后一个方案
     * 覆盖前一个的索引，实验结果互相污染。
     */
    private final Function<List<Chunk>, RetrieverFactory> factoryProvider;

    public DiagnosisEngine(Function<List<Chunk>, RetrieverFactory> factoryProvider) {
        if (factoryProvider == null) {
            throw new IllegalArgumentException("factoryProvider 不能为空");
        }
        this.factoryProvider = factoryProvider;
    }

    /**
     * 便捷构造：工厂不随语料变化时用这个。
     *
     * <p>测试里常用——假召回器不关心语料长什么样。
     * 真实场景应该用上面那个按语料现造的版本。
     */
    public DiagnosisEngine(RetrieverFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory 不能为空");
        }
        this.factoryProvider = corpus -> factory;
    }

    /**
     * 跑一次诊断。
     *
     * <p>基准取<b>第一个切分方案 × 第一个召回方案</b>。这是刻意的约定：
     * 调用方应该把"现在线上用的那套"放第一位，
     * 这样报告里所有差值都是相对现状算的，最符合用户看报告的习惯。
     */
    public DiagnosisReport diagnose(DiagnosisRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }

        Map<ExperimentId, ExperimentResult> results = new LinkedHashMap<>();
        Map<String, Integer> corpusSizes = new LinkedHashMap<>();
        List<String> caseIds = null;
        long negativeCount = 0;
        int caseCount = 0;

        for (ExperimentPlan plan : request.chunkings()) {
            // 每个切分方案各切一遍，并把测试集的期望文字重新落到这批片段上——
            // 不同切分切出来的片段不一样，不能复用上一轮的对应关系
            List<Chunk> corpus = plan.chunk(request.documents());
            corpusSizes.put(plan.id(), corpus.size());

            EvaluationSet evalSet = request.caseSpec().materialize(corpus);
            if (caseIds == null) {
                caseIds = evalSet.cases().stream().map(GoldenCase::id).toList();
                negativeCount = evalSet.negativeCount();
                caseCount = evalSet.size();
            }

            RetrieverFactory factory = factoryProvider.apply(corpus);

            for (RetrieverConfig retrieverConfig : request.retrievers()) {
                ExperimentId experimentId = new ExperimentId(plan.id(), retrieverConfig.id());
                results.put(experimentId,
                        runExperiment(plan, retrieverConfig, factory, evalSet));
            }
        }

        ExperimentId baselineId = new ExperimentId(
                request.chunkings().get(0).id(),
                request.retrievers().get(0).id());

        List<String> findings = deriveFindings(
                request, results, baselineId, corpusSizes, caseCount, negativeCount);

        return new DiagnosisReport(
                request.corpusName(),
                request.documentCount(),
                caseIds == null ? List.of() : caseIds,
                negativeCount,
                baselineId,
                results,
                corpusSizes,
                findings);
    }

    /**
     * 跑一次实验：对测试集每个用例执行召回，再算各项指标。
     */
    private ExperimentResult runExperiment(ExperimentPlan plan,
                                           RetrieverConfig retrieverConfig,
                                           RetrieverFactory factory,
                                           EvaluationSet evalSet) {
        Retriever retriever = factory.create(retrieverConfig, plan.topK(), plan.rerank());
        int topK = plan.topK();

        List<CaseOutcome> outcomes = new ArrayList<>(evalSet.size());
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

        return new ExperimentResult(plan, retrieverConfig, scores, outcomes);
    }

    /**
     * 用固定规则从指标推导结论。
     *
     * <p>规则刻意保持简单，因为用户需要看懂"为什么得出这个结论"。
     * 每条结论都要能对应到报告里的具体数字。
     */
    private List<String> deriveFindings(DiagnosisRequest request,
                                        Map<ExperimentId, ExperimentResult> results,
                                        ExperimentId baselineId,
                                        Map<String, Integer> corpusSizes,
                                        int caseCount,
                                        long negativeCount) {
        List<String> findings = new ArrayList<>();
        ExperimentResult baseline = results.get(baselineId);
        int topK = baseline.plan().topK();

        String recallName = "Recall@" + topK;
        String mrrName = "MRR@" + topK;

        findings.add(String.format(
                "%d 个文档，%d 个用例（负样本 %d 个），共跑 %d 次实验",
                request.documentCount(), caseCount, negativeCount, results.size()));

        if (negativeCount == 0) {
            findings.add("⚠ 测试集里没有负样本。检索器可能靠「总是返回最相似的内容」"
                    + "拿到虚高的分数，建议补几条「本来就该答不出来」的问题。");
        }

        // 规则一：召回够不够
        double baselineRecall = baseline.score(recallName);
        if (baselineRecall < SUFFICIENT_RECALL) {
            findings.add(String.format(
                    "基准（%s）%s = %.3f，召回不够：约 %.0f%% 的期望片段没进候选。"
                            + "这种情况下再优化排序也提升不了上限，"
                            + "应该先看切分粒度、向量模型是否合适、要不要加关键词召回。",
                    baseline.displayName(), recallName, baselineRecall,
                    (1 - baselineRecall) * 100));
        } else {
            findings.add(String.format(
                    "基准（%s）%s = %.3f，召回基本够用，瓶颈更可能在排序环节。",
                    baseline.displayName(), recallName, baselineRecall));
        }

        // 规则二：排序质量够不够
        if (baselineRecall >= SUFFICIENT_RECALL
                && baseline.score(mrrName) < WEAK_RANKING_THRESHOLD) {
            findings.add(String.format(
                    "基准的 %s = %.3f 偏低，但召回够用——这是「排序问题」而不是"
                            + "「召回问题」的典型表现。下一步该试重排，而不是继续调切分。",
                    mrrName, baseline.score(mrrName)));
        }

        // 规则三：能给结论的就给，给不了的说明为什么
        //
        // 这两件事是可以分别回答的：
        //   - 重排有没有用：拿"同一个切分、一加一不加"的配对方案比
        //   - 切分有没有用：拿"都不加重排"的方案之间比
        // 所以即使一次跑了很多组合，也不必含糊地说"没法归因"。
        boolean hasRerankPair = hasRerankPair(request.chunkings());
        if (hasRerankPair) {
            deriveRerankFindings(request, results, recallName, mrrName, findings);
        }
        if (hasChunkingComparison(request.chunkings())) {
            deriveChunkingFindings(request, results, recallName, findings);
        }
        if (!hasRerankPair && !hasChunkingComparison(request.chunkings())) {
            findings.add("这批实验里切分和重排都不一样，指标差异没法归因到具体哪一项。"
                    + "想单独看某项的影响，请让其他条件保持一致。");
        }

        // 规则四：换召回方式有没有收益
        deriveRetrieverFindings(results, baseline, recallName, mrrName, findings);

        return List.copyOf(findings);
    }

    /** 有没有"同一刀切分、一加一不加"的配对方案。有才谈得上验证重排。 */
    private static boolean hasRerankPair(List<ExperimentPlan> plans) {
        for (ExperimentPlan withRerank : plans) {
            if (!withRerank.rerank()) {
                continue;
            }
            String strategyId = idOf(withRerank.strategy());
            for (ExperimentPlan without : plans) {
                if (!without.rerank() && idOf(without.strategy()).equals(strategyId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 有没有两种以上"都不加重排"的不同切分。有才谈得上比较切分。 */
    private static boolean hasChunkingComparison(List<ExperimentPlan> plans) {
        Set<String> distinctStrategies = new HashSet<>();
        for (ExperimentPlan plan : plans) {
            if (!plan.rerank()) {
                distinctStrategies.add(idOf(plan.strategy()));
            }
        }
        return distinctStrategies.size() >= 2;
    }

    /** 用一个稳定可比的标识代表切分策略，用来判断"是不是同一种切分"。 */
    private static String idOf(cn.qiyec.ragsight.domain.knowledge.chunking.ChunkingStrategy strategy) {
        return strategy.getClass().getName() + "|" + strategy.describe();
    }

    /**
     * 判断加不加重排到底有没有用。
     *
     * <p>这是报告给出"该试重排"建议之后，用户真正要验证的那一步。
     * 比对时只比"同一个切分下、同一种召回，加与不加的区别"。
     */
    private void deriveRerankFindings(DiagnosisRequest request,
                                      Map<ExperimentId, ExperimentResult> results,
                                      String recallName,
                                      String mrrName,
                                      List<String> findings) {
        List<ExperimentPlan> plans = request.chunkings();
        if (plans.size() < 2) {
            return;
        }

        // 第一个方案当"不加重排"的对照（调用方应该把现状放第一位）
        ExperimentPlan without = plans.get(0);
        if (without.rerank()) {
            findings.add("提示：第一个方案已经带重排了，没法和不带重排的做对照。"
                    + "把不带重排的方案放第一位才能比出来。");
            return;
        }

        for (int i = 1; i < plans.size(); i++) {
            ExperimentPlan with = plans.get(i);
            if (!with.rerank()) {
                continue;
            }
            for (RetrieverConfig retriever : request.retrievers()) {
                ExperimentResult before = results.get(new ExperimentId(without.id(), retriever.id()));
                ExperimentResult after = results.get(new ExperimentId(with.id(), retriever.id()));
                if (before == null || after == null) {
                    continue;
                }

                double mrrGain = after.score(mrrName) - before.score(mrrName);
                double recallGain = after.score(recallName) - before.score(recallName);

                if (mrrGain >= NOTABLE_DELTA) {
                    findings.add(String.format(
                            "✅ 重排确实有用：在「%s」下加上重排，%s 从 %.3f 提到 %.3f（+%.3f）。",
                            retriever.displayName(), mrrName,
                            before.score(mrrName), after.score(mrrName), mrrGain));
                } else if (mrrGain > -NOTABLE_DELTA) {
                    findings.add(String.format(
                            "重排在这个语料上几乎没效果（%s 只变了 %+.3f）——"
                                    + "不值得为它增加延迟和一次模型调用。",
                            mrrName, mrrGain));
                } else {
                    findings.add(String.format(
                            "⚠ 加重排反而变差了（%s 下降了 %.3f）。"
                                    + "常见原因是召回本身就不够——候选里没有正确答案，"
                                    + "重排只能把错的排得更整齐。先解决召回问题。",
                            mrrName, -mrrGain));
                }

                // 重排理论上不该改变召回率，变了说明有问题
                if (Math.abs(recallGain) >= NOTABLE_DELTA) {
                    findings.add(String.format(
                            "⚠ 异常：加重排后召回率也变了 %+.3f。"
                                    + "重排只在已有候选里重排，理论上不该改变召回率，"
                                    + "这个现象值得查一下。",
                            recallGain));
                }
                return; // 只报第一组对照，免得结论堆砌
            }
        }
    }

    /**
     * 判断切分粒度/方式有没有实质影响。
     *
     * <p>这是用户最常问、也最容易被瞎折腾的一件事。
     * 如果换了切分指标几乎不动，就明确告诉他"不用在这上面花时间"。
     */
    private void deriveChunkingFindings(DiagnosisRequest request,
                                        Map<ExperimentId, ExperimentResult> results,
                                        String recallName,
                                        List<String> findings) {
        if (request.chunkings().size() < 2) {
            return;
        }

        // 只比"不加重排"的那些方案。这样组内差异只可能来自切分，
        // 不会把"重排带来的提升"误算成"切分的影响"。
        Map<String, ExperimentResult> bestPerStrategy = new LinkedHashMap<>();

        for (ExperimentPlan plan : request.chunkings()) {
            if (plan.rerank()) {
                continue;
            }
            String strategyId = idOf(plan.strategy());
            for (RetrieverConfig retriever : request.retrievers()) {
                ExperimentResult result = results.get(new ExperimentId(plan.id(), retriever.id()));
                if (result == null) {
                    continue;
                }
                bestPerStrategy.merge(strategyId, result,
                        (a, b) -> a.score(recallName) >= b.score(recallName) ? a : b);
            }
        }

        if (bestPerStrategy.size() < 2) {
            return; // 只有一种切分，无从比较
        }

        double min = bestPerStrategy.values().stream()
                .mapToDouble(r -> r.score(recallName)).min().orElse(0);
        double max = bestPerStrategy.values().stream()
                .mapToDouble(r -> r.score(recallName)).max().orElse(0);
        double spread = max - min;

        if (spread < NOTABLE_DELTA) {
            findings.add(String.format(
                    "换切分方式对结果几乎没影响（各切分方案的 %s 差异只有 %.3f）"
                            + "——不用在切分粒度上继续花时间。",
                    recallName, spread));
        } else {
            ExperimentResult best = bestPerStrategy.values().stream()
                    .max(Comparator.comparingDouble(r -> r.score(recallName)))
                    .orElseThrow();
            findings.add(String.format(
                    "切分确实有影响：「%s」的 %s 最高（%.3f），比其他切分方案至少高 %.3f。",
                    best.plan().displayName(), recallName,
                    best.score(recallName), spread));
            findings.add("⚠ 但别急着下结论：片段切得越大，一个片段里装的内容越多，"
                    + "命中率天然就越高——这不一定说明检索变好了。"
                    + "还要看喂给模型的上下文里有多少是无关内容（片段越大越多），"
                    + "以及总片段数是否少到「返回前 K 条」已经接近返回全部。");
        }
    }

    /**
     * 判断换召回方式有没有收益，并明确指出哪些改动不值得做。
     */
    private void deriveRetrieverFindings(Map<ExperimentId, ExperimentResult> results,
                                         ExperimentResult baseline,
                                         String recallName,
                                         String mrrName,
                                         List<String> findings) {
        // 只比"同一个切分方案下"的不同召回方式，才是干净的对比
        List<ExperimentResult> sameChunking = results.values().stream()
                .filter(r -> r.plan().id().equals(baseline.plan().id()))
                .toList();

        ExperimentResult best = null;
        double bestGain = 0;
        for (ExperimentResult r : sameChunking) {
            if (r.retriever().id().equals(baseline.retriever().id())) {
                continue;
            }
            double gain = r.score(recallName) - baseline.score(recallName);
            if (gain > bestGain) {
                bestGain = gain;
                best = r;
            }
        }

        if (best != null && bestGain >= NOTABLE_DELTA) {
            findings.add(String.format(
                    "✅ 在同一套切分下，换成「%s」能把 %s 从 %.3f 提到 %.3f（+%.3f）。"
                            + "这是本次诊断里最值得做的一处改动。",
                    best.retriever().displayName(), recallName,
                    baseline.score(recallName), best.score(recallName), bestGain));
        }

        // 明确说清哪些改动不值得做。"不用改"和"要改"一样有价值。
        List<String> notWorth = new ArrayList<>();
        for (ExperimentResult r : sameChunking) {
            if (r.retriever().id().equals(baseline.retriever().id())) {
                continue;
            }
            double recallDelta = r.score(recallName) - baseline.score(recallName);
            double mrrDelta = r.score(mrrName) - baseline.score(mrrName);
            if (Math.abs(recallDelta) < NOTABLE_DELTA && mrrDelta < NOTABLE_DELTA) {
                notWorth.add(r.retriever().displayName());
            }
        }
        if (!notWorth.isEmpty()) {
            findings.add(String.format(
                    "以下方案相对基准没什么变化（提升都小于 %.2f），"
                            + "不值得为此增加复杂度：%s",
                    NOTABLE_DELTA, String.join("、", notWorth)));
        }
    }
}
