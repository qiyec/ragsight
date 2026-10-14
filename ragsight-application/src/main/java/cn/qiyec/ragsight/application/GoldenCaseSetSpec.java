package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.evaluation.EvaluationSet;
import cn.qiyec.ragsight.domain.evaluation.GoldenCase;
import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一整份测试集的原始描述——从文件读出来、还没和片段对应上。
 *
 * <p>它的核心职责是 {@link #materialize}：把"要找的文字"落到实际片段上。
 * 这一步必须等语料切分完才能做，所以拆成了两个阶段。
 */
public record GoldenCaseSetSpec(String name, List<GoldenCaseSpec> cases) {

    public GoldenCaseSetSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("测试集名称不能为空");
        }
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("测试集里至少要有一条用例");
        }
        cases = List.copyOf(cases);

        Set<String> ids = new LinkedHashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (GoldenCaseSpec c : cases) {
            if (!ids.add(c.id())) {
                duplicated.add(c.id());
            }
        }
        if (!duplicated.isEmpty()) {
            throw new IllegalArgumentException(
                    "测试集里的用例 id 重复了，报告就没法区分是哪一条：" + duplicated);
        }
    }

    /** 用例数量。 */
    public int size() {
        return cases.size();
    }

    /** 负样本数量（只有问题、没有期望片段的那些）。 */
    public long negativeCount() {
        return cases.stream().filter(GoldenCaseSpec::isNegative).count();
    }

    /**
     * 把用例落到实际片段上，得到可以直接给诊断引擎用的测试集。
     *
     * <p>找不到期望文字时会**直接报错**，不跳过。这是刻意的：
     * 静默跳过会让测试集悄悄失效——指标还能算出来，但算的是错的，
     * 而且看不出哪里不对。直接报错虽然打断流程，但能立刻发现问题。
     *
     * @param corpus 已经切分好的语料
     * @return 可用的测试集
     */
    public EvaluationSet materialize(List<Chunk> corpus) {
        if (corpus == null || corpus.isEmpty()) {
            throw new IllegalArgumentException("语料为空，无法把测试集对应到片段上");
        }

        List<GoldenCase> materialized = new ArrayList<>(cases.size());
        List<String> problems = new ArrayList<>();

        for (GoldenCaseSpec spec : cases) {
            if (spec.isNegative()) {
                materialized.add(GoldenCase.of(spec.id(), spec.question(), List.of()));
                continue;
            }

            Set<ChunkId> matched = new LinkedHashSet<>();
            List<String> missing = new ArrayList<>();

            for (String marker : spec.expectedMarkers()) {
                List<ChunkId> found = findChunksContaining(corpus, marker);
                if (found.isEmpty()) {
                    missing.add(marker);
                } else {
                    matched.addAll(found);
                }
            }

            if (matched.isEmpty()) {
                problems.add(String.format(
                        "用例「%s」的期望文字在语料里一条都没找到：%s",
                        spec.id(), String.join(" | ", missing)));
                continue;
            }
            if (!missing.isEmpty()) {
                // 部分找不到也报出来，但先不中断——只要有一条能对上，用例仍然可用
                problems.add(String.format(
                        "用例「%s」有部分期望文字没找到（其余仍参与评测）：%s",
                        spec.id(), String.join(" | ", missing)));
            }

            materialized.add(GoldenCase.of(spec.id(), spec.question(),
                    List.copyOf(matched)));
        }

        if (materialized.size() < cases.size() / 2 + 1) {
            throw new IllegalStateException(
                    "超过一半的用例都对应不到片段，测试集文件多半和语料对不上：\n  - "
                            + String.join("\n  - ", problems));
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "测试集和语料对不上，请检查期望文字是否写错：\n  - "
                            + String.join("\n  - ", problems));
        }

        return EvaluationSet.of(name, materialized);
    }

    /**
     * 找出里面含有这段文字的所有片段。
     *
     * <p>返回**全部**匹配的片段，而不是只取第一个：同一段内容可能因为
     * 切分重叠而出现在多个片段里，只标第一个的话，召回另一个正确片段
     * 会被算成没召回，白白拉低分数。
     */
    private static List<ChunkId> findChunksContaining(List<Chunk> corpus, String marker) {
        List<ChunkId> matched = new ArrayList<>();
        for (Chunk chunk : corpus) {
            if (chunk.text().contains(marker)) {
                matched.add(chunk.id());
            }
        }
        return matched;
    }

    /** 按 id 索引，便于测试和调试。 */
    public Map<String, GoldenCaseSpec> byId() {
        Map<String, GoldenCaseSpec> map = new LinkedHashMap<>();
        cases.forEach(c -> map.put(c.id(), c));
        return Map.copyOf(map);
    }
}
