package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.evaluation.GoldenCase;
import cn.qiyec.ragsight.domain.evaluation.RetrievedEvidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个用例在一次实验中的结果摘要。
 *
 * <p><b>为什么需要这个类型</b>：诊断报告要展示"哪些问题失败了"，
 * 这需要知道"期望片段被排在什么位置"。若让报告渲染器自己去查评测集，
 * 渲染器就得持有评测集引用，变得有状态、难以独立测试。
 *
 * <p>因此在实验阶段就把归因所需的信息固化成摘要：用例标识 + 各期望片段的位置。
 * 报告渲染器只需读摘要，不依赖任何标注数据。
 *
 * @param caseId       用例标识
 * @param isNegative   是否为负样本（无期望片段）
 * @param hitPositions 期望片段 → 排名；-1 表示未召回
 */
public record CaseOutcome(
        String caseId,
        boolean isNegative,
        Map<String, Integer> hitPositions) {

    public CaseOutcome {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId 不能为空");
        }
        hitPositions = hitPositions == null ? Map.of() : Map.copyOf(hitPositions);
    }

    /**
     * 由标注用例与实际召回结果构造摘要。
     */
    public static CaseOutcome of(GoldenCase testCase, RetrievedEvidence evidence) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (var expected : testCase.expectedChunkIds()) {
            positions.put(expected.toString(), evidence.rankOf(expected));
        }
        return new CaseOutcome(testCase.id(), testCase.isNegative(), positions);
    }

    /**
     * 最佳命中排名——所有期望片段中最靠前的那个。
     *
     * <p>语义与 MRR 一致：只要有一个期望片段靠前，用户就能拿到正确上下文。
     *
     * @return 排名；未命中返回 -1；负样本返回 0（无期望片段，不参与排名语义）
     */
    public int bestRank() {
        if (isNegative) {
            return 0;
        }
        return hitPositions.values().stream()
                .filter(rank -> rank > 0)
                .mapToInt(Integer::intValue)
                .min()
                .orElse(-1);
    }

    /** 是否有任何期望片段被召回。负样本恒为 false。 */
    public boolean anyHit() {
        return bestRank() > 0;
    }

    /** 期望片段总数。 */
    public int expectedCount() {
        return hitPositions.size();
    }
}
