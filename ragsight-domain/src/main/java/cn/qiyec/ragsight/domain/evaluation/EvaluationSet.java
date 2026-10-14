package cn.qiyec.ragsight.domain.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试集：一组标注好的用例。
 *
 * <p>它负责把关一件事：<b>用例 id 不能重复</b>。
 * 这个检查只能放在集合这一层做，因为单个用例看不到别的用例。
 * 为什么重要：id 重复会让"按用例查失败原因"直接失真——
 * 报告里说"q-001 没召回到"，但你不知道是哪个 q-001。
 *
 * <p>构造完之后不可变，所以同一份测试集可以反复用于多次实验。
 */
public record EvaluationSet(String name, List<GoldenCase> cases) {

    public EvaluationSet {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("评测集名称不能为空");
        }
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("评测集不能为空：" + name);
        }
        cases = List.copyOf(cases);

        // 这个类最关键的职责：保证用例 id 唯一
        Map<String, Long> duplicated = cases.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        GoldenCase::id, java.util.stream.Collectors.counting()));
        List<String> duplicates = duplicated.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "评测集内用例 id 重复，会导致归因失真：" + duplicates);
        }
    }

    public static EvaluationSet of(String name, List<GoldenCase> cases) {
        return new EvaluationSet(name, cases);
    }

    /** 用例数量。 */
    public int size() {
        return cases.size();
    }

    public Optional<GoldenCase> findById(String id) {
        return cases.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /**
     * 负样本数量。
     *
     * <p>诊断报告必须展示这个数字：负样本占比过低的评测集会高估检索器能力。
     */
    public long negativeCount() {
        return cases.stream().filter(GoldenCase::isNegative).count();
    }

    /**
     * 按用例 id 索引，便于与评估结果对齐。
     */
    public Map<String, GoldenCase> byId() {
        Map<String, GoldenCase> map = new LinkedHashMap<>();
        cases.forEach(c -> map.put(c.id(), c));
        return Map.copyOf(map);
    }
}
