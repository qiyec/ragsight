package cn.qiyec.ragsight.domain.evaluation;

import cn.qiyec.ragsight.domain.knowledge.ChunkId;

import java.util.Set;

/**
 * 召回率 Recall@k：期望证据中，有多少比例出现在前 k 个结果里。
 *
 * <pre>
 *   Recall@k = |期望片段 ∩ 前k个召回片段| / |期望片段|
 * </pre>
 *
 * <p><b>为什么召回率是诊断的第一个必看指标</b>：
 * 它衡量的是"正确答案有没有被捞进候选池"。召回率为 0.6 意味着
 * 四成的问题连候选都没有——这种情况下无论重排多强、上下文拼得多好，
 * 都不可能答对。所以诊断顺序永远是：<b>先看 Recall，再看排序质量</b>。
 *
 * <p><b>负样本处理</b>：期望集合为空时定义为满分 1.0。
 * 语义是"没有该找的证据，也就不存在漏找"。
 */
public record RecallAtK(int k) implements Metric {

    public RecallAtK {
        if (k <= 0) {
            throw new IllegalArgumentException("k 必须为正数，实际为 " + k);
        }
    }

    @Override
    public String name() {
        return "Recall@" + k;
    }

    @Override
    public double score(GoldenCase testCase, RetrievedEvidence evidence) {
        if (testCase.isNegative()) {
            return 1.0;
        }

        Set<ChunkId> expected = testCase.expectedChunkIds();
        Set<ChunkId> retrievedTopK = evidence.topK(k).chunkIds();

        long hit = expected.stream().filter(retrievedTopK::contains).count();
        return (double) hit / expected.size();
    }
}
