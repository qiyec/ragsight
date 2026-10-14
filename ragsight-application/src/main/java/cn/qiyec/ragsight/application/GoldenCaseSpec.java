package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.evaluation.GoldenCase;
import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一条测试用例的原始描述——还没和具体片段对应上。
 *
 * <p><b>为什么要分成两步（先描述、后对应）</b>：
 * 测试集文件里写的是"期望召回的文字"，而不是片段编号。
 * 片段编号取决于切分参数，写死之后一旦调整切分就会指向不存在的片段，
 * 指标全变 0，而你多半会以为是检索坏了。
 *
 * <p>所以先记下"要找什么文字"，等语料切分完再去找对应的片段。
 * 这样切分怎么调，测试集都不用改。
 *
 * @param id              用例标识
 * @param question        用户问题
 * @param expectedMarkers 期望召回的片段里应当出现的文字；
 *                        为空表示负样本（这个问题本来就该答不出来）
 */
public record GoldenCaseSpec(String id, String question, List<String> expectedMarkers) {

    public GoldenCaseSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("用例 id 不能为空");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空：" + id);
        }
        expectedMarkers = expectedMarkers == null ? List.of() : List.copyOf(expectedMarkers);
    }

    /** 是否是负样本（没有期望片段）。 */
    public boolean isNegative() {
        return expectedMarkers.isEmpty();
    }
}
