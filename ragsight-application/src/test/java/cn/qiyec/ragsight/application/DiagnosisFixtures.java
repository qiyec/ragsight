package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.evaluation.EvaluationSet;
import cn.qiyec.ragsight.domain.evaluation.GoldenCase;
import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.knowledge.DocumentId;
import cn.qiyec.ragsight.domain.retrieval.RetrievalSource;
import cn.qiyec.ragsight.domain.retrieval.Retriever;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的小工具：造语料、造测试集、造"我想让它返回什么就返回什么"的假召回器。
 */
final class DiagnosisFixtures {

    private DiagnosisFixtures() {
    }

    /** 造一个片段。id 形如 {@code doc#0}。 */
    static Chunk chunk(String docId, int ordinal) {
        ChunkId id = new ChunkId(DocumentId.of(docId), ordinal);
        return new Chunk(id, id.documentId(), ordinal, docId + " 的第 " + ordinal + " 段");
    }

    /** 造一个标注用例。expected 为空表示负样本。 */
    static GoldenCase goldenCase(String caseId, int ordinal) {
        return new GoldenCase(caseId, "问题-" + caseId,
                java.util.Set.of(chunkId("d", ordinal)),
                java.util.Optional.empty(), java.util.Optional.empty());
    }

    /** 造一个负样本用例（期望为空，即"这个问题本来就该答不出来"）。 */
    static GoldenCase negativeCase(String caseId) {
        return new GoldenCase(caseId, "问题-" + caseId, java.util.Set.of(),
                java.util.Optional.empty(), java.util.Optional.empty());
    }

    static ChunkId chunkId(String docId, int ordinal) {
        return new ChunkId(DocumentId.of(docId), ordinal);
    }

    /**
     * 造一个假召回器：对每个用例返回预先指定的片段，按给定顺序。
     *
     * <p>这样测试就能精确控制"哪个用例命中、排第几"，从而构造出
     * 各种已知缺陷场景——比如"召回不到"或"召回到了但排得很后"。
     */
    static Retriever scripted(Map<String, List<ChunkId>> byQuestion) {
        return (query, topK) -> {
            List<ChunkId> ids = byQuestion.getOrDefault(query, List.of());
            List<ScoredChunk> result = new ArrayList<>();
            int rank = 1;
            for (ChunkId id : ids) {
                if (rank > topK) {
                    break;
                }
                Chunk chunk = new Chunk(id, id.documentId(), id.ordinal(), "片段 " + id);
                result.add(new ScoredChunk(chunk, 1.0 / rank, rank, RetrievalSource.VECTOR));
                rank++;
            }
            return result;
        };
    }

    /** 按配置 id 指定各自的假召回器。 */
    static RetrieverFactory factory(Map<String, Retriever> byConfigId) {
        return config -> {
            Retriever retriever = byConfigId.get(config.id());
            if (retriever == null) {
                throw new IllegalArgumentException("测试没有为配置 " + config.id() + " 准备召回器");
            }
            return retriever;
        };
    }

    /** 便捷构造：一个用例 → 返回片段列表的映射。 */
    static Map<String, List<ChunkId>> script(String question, List<ChunkId> ids) {
        Map<String, List<ChunkId>> map = new LinkedHashMap<>();
        map.put(question, ids);
        return map;
    }

    /** 合并多个 script。 */
    @SafeVarargs
    static Map<String, List<ChunkId>> scripts(Map<String, List<ChunkId>>... maps) {
        Map<String, List<ChunkId>> merged = new LinkedHashMap<>();
        for (Map<String, List<ChunkId>> m : maps) {
            merged.putAll(m);
        }
        return merged;
    }

    static List<ChunkId> ids(ChunkId... ids) {
        return Arrays.asList(ids);
    }

    /** 造一个诊断请求。基准是 configs 里的第一个。 */
    static DiagnosisRequest request(EvaluationSet evalSet, List<RetrieverConfig> configs) {
        List<Chunk> corpus = List.of(chunk("d", 0), chunk("d", 1), chunk("d", 2), chunk("d", 3));
        return new DiagnosisRequest("测试语料", corpus, evalSet, configs);
    }

    static EvaluationSet evalSetOf(GoldenCase... cases) {
        return EvaluationSet.of("测试集", Arrays.asList(cases));
    }

    /** 找出结论里包含指定关键词的那条，方便断言。 */
    static String findingContaining(DiagnosisReport report, String keyword) {
        return report.findings().stream()
                .filter(f -> f.contains(keyword))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "结论里没有包含「" + keyword + "」的内容。实际结论：\n  - "
                                + String.join("\n  - ", report.findings())));
    }
}
