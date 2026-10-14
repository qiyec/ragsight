package cn.qiyec.ragsight.application;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断报告——一次诊断的完整结论。
 *
 * <p>它是本工具的**最终交付物**：用户拿到的就是这份报告，
 * 并据此决定改什么、以及**不**改什么。
 *
 * <p><b>为什么报告不引入 LLM 生成结论</b>：
 * 报告里的每个数字都必须是可复现的。结论（如"瓶颈在召回环节"）
 * 由确定性规则从指标推导，因此同一份输入永远得到同一份报告。
 * 用 LLM 润色措辞是可以的，但**判断必须来自数字**（见 ADR-001）。
 *
 * @param corpusName       语料名称
 * @param caseCount        评测集用例数
 * @param negativeCaseCount 负样本数量
 * @param baselineConfigId 作为对比基准的配置 id
 * @param results          各实验的结果，键为配置 id，保持配置传入顺序
 * @param findings         诊断发现（确定性规则推导的结论）
 */
public record DiagnosisReport(
        String corpusName,
        int caseCount,
        long negativeCaseCount,
        List<String> caseIds,
        String baselineConfigId,
        Map<String, ExperimentResult> results,
        List<String> findings) {

    public DiagnosisReport {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个实验结果");
        }
        if (baselineConfigId == null || !results.containsKey(baselineConfigId)) {
            throw new IllegalArgumentException(
                    "基准配置 id 必须在结果中存在：" + baselineConfigId);
        }
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        results = Map.copyOf(results);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** 取基准实验。 */
    public ExperimentResult baseline() {
        return results.get(baselineConfigId);
    }

    /** 取指定配置的结果。 */
    public ExperimentResult resultOf(String configId) {
        ExperimentResult result = results.get(configId);
        if (result == null) {
            throw new IllegalArgumentException("不存在该配置的结果：" + configId);
        }
        return result;
    }

    /** 报告涉及的所有指标名，按首次出现顺序。 */
    public List<String> metricNames() {
        return results.values().stream()
                .findFirst()
                .map(r -> List.copyOf(r.metricScores().keySet()))
                .orElse(List.of());
    }

    /**
     * 按指定指标对所有实验降序排名。
     *
     * <p>同分时按配置 id 排序，保证报告可复现。
     */
    public List<ExperimentResult> rankedBy(String metricName) {
        return results.values().stream()
                .sorted(Comparator
                        .comparingDouble((ExperimentResult r) -> r.score(metricName)).reversed()
                        .thenComparing(r -> r.config().id()))
                .toList();
    }

    /**
     * 找出指定指标的最优实验。
     *
     * <p>若最优实验就是基线，说明改动无收益——报告应明确说出来，
     * 因为"这个改动不值得做"和"这个改动很有用"同样有价值。
     */
    public ExperimentResult bestBy(String metricName) {
        return rankedBy(metricName).get(0);
    }

    /**
     * 生成"相对基线"的对比视图：指标名 → 各配置相对基线的差值。
     *
     * <p>这是报告最有价值的部分。用户不关心"混合检索的 Recall 是 0.81"，
     * 而关心"比我现在用的方案好多少"。
     */
    public Map<String, Map<String, Double>> deltasAgainstBaseline() {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ExperimentResult> entry : results.entrySet()) {
            if (entry.getKey().equals(baselineConfigId)) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue().deltasAgainst(baseline()));
        }
        return Map.copyOf(result);
    }

    /** 实验数量。 */
    public int experimentCount() {
        return results.size();
    }

    /** 取指定下标用例的标识，供报告逐用例展示。 */
    public String caseIdAt(int caseIndex) {
        if (caseIndex < 0 || caseIndex >= caseIds.size()) {
            return "#" + (caseIndex + 1);
        }
        return caseIds.get(caseIndex);
    }
}
