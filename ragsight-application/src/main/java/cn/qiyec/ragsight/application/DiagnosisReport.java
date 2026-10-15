package cn.qiyec.ragsight.application;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 诊断报告——一次诊断的完整结论。
 *
 * <p>它是这个工具的最终交付物：用户拿到的就是它，并据此决定改什么、
 * 以及**不**改什么。
 *
 * <p><b>为什么报告里的结论不用模型生成</b>：
 * 每个数字都必须能复现。结论（比如"瓶颈在召回"）由固定规则从指标推出来，
 * 所以同样的输入永远得到同样的报告。用模型润色措辞可以，
 * 但**判断必须来自数字**。
 *
 * @param corpusName      语料名称
 * @param documentCount   文档数量
 * @param caseIds         用例标识，顺序与测试集一致
 * @param negativeCaseCount 负样本数量
 * @param baselineId      作为基准的那次实验
 * @param results         各次实验的结果，保持跑的顺序
 * @param corpusSizes     各切分方案切出来的片段数
 * @param findings        诊断结论
 */
public record DiagnosisReport(
        String corpusName,
        int documentCount,
        List<String> caseIds,
        long negativeCaseCount,
        ExperimentId baselineId,
        Map<ExperimentId, ExperimentResult> results,
        Map<String, Integer> corpusSizes,
        List<String> findings) {

    public DiagnosisReport {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("至少需要一次实验的结果");
        }
        if (baselineId == null || !results.containsKey(baselineId)) {
            throw new IllegalArgumentException("基准实验必须在结果里：" + baselineId);
        }
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        // 用 LinkedHashMap 包一层，**不能用 Map.copyOf**：
        // Map.copyOf 只保证不可变，不保证保留插入顺序。而结果的顺序
        // 决定了报告里表格的行序和热力图的行列顺序——顺序乱了，
        // 同一个页面刷新两次列的位置可能都不一样，很难看懂。
        results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
        corpusSizes = corpusSizes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(corpusSizes));
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** 取基准实验。 */
    public ExperimentResult baseline() {
        return results.get(baselineId);
    }

    /** 取指定实验的结果。 */
    public ExperimentResult resultOf(ExperimentId id) {
        ExperimentResult result = results.get(id);
        if (result == null) {
            throw new IllegalArgumentException("没有这次实验的结果：" + id);
        }
        return result;
    }

    /** 报告里出现的所有指标名，按第一次出现的顺序。 */
    public List<String> metricNames() {
        return results.values().stream()
                .findFirst()
                .map(r -> List.copyOf(r.metricScores().keySet()))
                .orElse(List.of());
    }

    /**
     * 按指定指标把所有实验从高到低排。
     *
     * <p>同分时按实验标识排，保证报告可复现。
     */
    public List<ExperimentResult> rankedBy(String metricName) {
        return results.values().stream()
                .sorted(Comparator
                        .comparingDouble((ExperimentResult r) -> r.score(metricName)).reversed()
                        .thenComparing(r -> r.id().toString()))
                .toList();
    }

    /** 某个指标表现最好的那次实验。 */
    public ExperimentResult bestBy(String metricName) {
        return rankedBy(metricName).get(0);
    }

    /**
     * 算出"相对基准"的变化：实验 → 指标 → 差值。
     *
     * <p>这是报告里最有用的部分。用户不关心"混合检索的 Recall 是 0.81"，
     * 关心的是"比我现在用的方案好多少"。
     */
    public Map<ExperimentId, Map<String, Double>> deltasAgainstBaseline() {
        Map<ExperimentId, Map<String, Double>> result = new LinkedHashMap<>();
        for (Map.Entry<ExperimentId, ExperimentResult> entry : results.entrySet()) {
            if (entry.getKey().equals(baselineId)) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue().deltasAgainst(baseline()));
        }
        return Map.copyOf(result);
    }

    /** 跑了多少次实验。 */
    public int experimentCount() {
        return results.size();
    }

    /** 用了多少种切分方案。 */
    public int chunkingCount() {
        return corpusSizes.size();
    }

    /** 用了多少种召回方案。 */
    public int retrieverCount() {
        return (int) results.values().stream()
                .map(r -> r.retriever().id())
                .distinct()
                .count();
    }

    /** 分成几组（每种切分一组）。 */
    public Set<String> chunkingIds() {
        return results.values().stream()
                .map(r -> r.plan().id())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** 取指定下标用例的标识。 */
    public String caseIdAt(int caseIndex) {
        if (caseIndex < 0 || caseIndex >= caseIds.size()) {
            return "#" + (caseIndex + 1);
        }
        return caseIds.get(caseIndex);
    }

    /** 评估用例数量。 */
    public int caseCount() {
        return caseIds.size();
    }
}
