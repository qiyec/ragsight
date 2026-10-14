package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.DocumentId;
import cn.qiyec.ragsight.domain.knowledge.chunking.ChunkingStrategy;
import cn.qiyec.ragsight.domain.retrieval.RetrievalSource;
import cn.qiyec.ragsight.domain.retrieval.Retriever;
import cn.qiyec.ragsight.domain.retrieval.ScoredChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的小工具：造文档、造测试集、造"我想让它返回什么就返回什么"的假召回器。
 *
 * <p><b>这里有个关键设计</b>：夹具提供的是**已经切好的片段**，
 * 而不是"一个文档 + 一个切分策略"。原因是切分策略要真的把文档切成多段，
 * 测试才能构造出"每个用例对应不同片段"的场景；如果策略把整篇合成一段，
 * 所有用例都会指向同一个片段，召回率永远是 1，什么都测不出来。
 *
 * <p>每个片段正文里带着一个"用例标记"，形如 {@code M<c1>}。
 * 测试集里写这个标记当期望文字，于是每个用例能精确对上唯一一个片段。
 */
final class DiagnosisFixtures {

    private DiagnosisFixtures() {
    }

    static final int TOP_K = 5;

    /** 造一个片段标识。 */
    static ChunkId chunkId(String docId, int ordinal) {
        return new ChunkId(DocumentId.of(docId), ordinal);
    }

    /** 用法例 id 造一个标记文字。 */
    static String marker(String caseId) {
        return "M<" + caseId + ">";
    }

    /** 问题文本：约定成 {@code 问题-<用例id>}。 */
    static String questionOf(String caseId) {
        return "问题-" + caseId;
    }

    /** 造一个片段，正文是"片段 id + 用例标记"。 */
    static Chunk chunk(String docId, int ordinal, String caseId) {
        ChunkId id = chunkId(docId, ordinal);
        return new Chunk(id, id.documentId(), ordinal,
                id + " " + (caseId == null ? "占位" : marker(caseId)));
    }

    /** 造一个不承载任何用例的片段（只当候选）。 */
    static Chunk filler(String docId, int ordinal) {
        return chunk(docId, ordinal, null);
    }

    /** 把片段拼成一个文档（正文用换行分隔）。 */
    static Document documentOf(String docId, List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            sb.append(c.text()).append('\n');
        }
        return Document.of(docId, sb.toString());
    }

    /**
     * 造一整套测试材料。
     *
     * <p>{@code caseCount} 个用例，各自精确对应第 i 个片段。
     * 另外补几个空片段把语料撑得比 topK 大——
     * 否则"语料比 topK 还少"会让"全都返回"看起来像"全都命中"。
     *
     * @param caseCount 用例数，也是带标记的片段数
     */
    static Fixture fixture(int caseCount) {
        List<Chunk> chunks = new ArrayList<>();
        List<GoldenCaseSpec> specs = new ArrayList<>();

        for (int i = 0; i < caseCount; i++) {
            String caseId = "c" + (i + 1);
            chunks.add(chunk("d", i, caseId));
            specs.add(new GoldenCaseSpec(caseId, questionOf(caseId), List.of(marker(caseId))));
        }
        int fillerCount = Math.max(TOP_K, caseCount) + 4;
        for (int i = caseCount; i < fillerCount; i++) {
            chunks.add(filler("d", i));
        }

        return new Fixture(documentOf("d", chunks), List.copyOf(chunks),
                new GoldenCaseSetSpec("测试集", specs));
    }

    /** 一套测试材料：文档、切好的片段、测试集。 */
    record Fixture(Document document, List<Chunk> chunks, GoldenCaseSetSpec caseSpec) {

        /** 第 {@code caseIndex} 个用例期望的片段。 */
        ChunkId expectedOf(int caseIndex) {
            return chunks.get(caseIndex).id();
        }

        /** 取一个不承载任何用例的片段，用来造"召回了但召回错了"。 */
        ChunkId fillerId() {
            return chunks.get(chunks.size() - 1).id();
        }

        /** 用例数。 */
        int caseCount() {
            return caseSpec.size();
        }
    }

    /**
     * 造一个"返回预置片段"的切分策略。
     *
     * <p>测试不关心真实切分算法，只关心"片段就是这些、每个用例对应其中一个"。
     * 所以直接返回夹具里准备好的片段，而不是真的去切文档。
     */
    static ExperimentPlan plan(String id, List<Chunk> presetChunks) {
        return plan(id, TOP_K, presetChunks);
    }

    static ExperimentPlan plan(String id, int topK, List<Chunk> presetChunks) {
        // 写成匿名类：ChunkingStrategy 除 chunk() 还有 name() 和 describe()，
        // 不是函数式接口，不能用 lambda
        ChunkingStrategy preset = new ChunkingStrategy() {
            @Override
            public String name() {
                return "preset";
            }

            @Override
            public String describe() {
                return "预置片段";
            }

            @Override
            public List<Chunk> chunk(Document document) {
                return presetChunks;
            }
        };
        return new ExperimentPlan(id, "方案 " + id, preset, topK);
    }

    /**
     * 造一个假召回器：按问题返回预先指定的片段，顺序也由你定。
     *
     * <p>这样才能精确构造出"召回不到"或"召回到了但排得很后"这些已知场景。
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
                result.add(new ScoredChunk(chunk(id.documentId().value(), id.ordinal(), null),
                        1.0 / rank, rank, RetrievalSource.VECTOR));
                rank++;
            }
            return result;
        };
    }

    /** 按召回方案的 id 指定各自的假召回器。 */
    static RetrieverFactory factory(Map<String, Retriever> byRetrieverId) {
        return (config, topK) -> {
            Retriever retriever = byRetrieverId.get(config.id());
            if (retriever == null) {
                throw new IllegalArgumentException(
                        "测试没有为召回方案 " + config.id() + " 准备召回器");
            }
            return retriever;
        };
    }

    /** 便捷构造：一个问题 → 返回的片段列表。 */
    static Map<String, List<ChunkId>> script(String question, List<ChunkId> ids) {
        Map<String, List<ChunkId>> map = new LinkedHashMap<>();
        map.put(question, ids);
        return map;
    }

    /** 把多个 script 合并。 */
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

    /** 用给定夹具造请求；默认只有一个切分方案。 */
    static DiagnosisRequest request(Fixture fixture) {
        return request(fixture, List.of(plan("p1", fixture.chunks())));
    }

    static DiagnosisRequest request(Fixture fixture, List<ExperimentPlan> plans) {
        return new DiagnosisRequest(
                "测试语料",
                List.of(fixture.document()),
                fixture.caseSpec(),
                plans,
                RetrieverConfig.standardBaselines());
    }

    /** 找一个包含指定关键词的结论，方便断言。 */
    static String findingContaining(DiagnosisReport report, String keyword) {
        return report.findings().stream()
                .filter(f -> f.contains(keyword))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "结论里没有包含「" + keyword + "」的内容。实际结论：\n  - "
                                + String.join("\n  - ", report.findings())));
    }

    /** 结论里是否含某个关键词。 */
    static boolean hasFindingContaining(DiagnosisReport report, String keyword) {
        return report.findings().stream().anyMatch(f -> f.contains(keyword));
    }
}
