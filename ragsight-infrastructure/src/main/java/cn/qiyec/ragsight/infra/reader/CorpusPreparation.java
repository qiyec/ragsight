package cn.qiyec.ragsight.infra.reader;

import cn.qiyec.ragsight.application.GoldenCaseSetSpec;
import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.DocumentReader;
import cn.qiyec.ragsight.domain.knowledge.chunking.ChunkingStrategy;
import cn.qiyec.ragsight.domain.knowledge.chunking.RecursiveCharacterChunkingStrategy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从磁盘上一批文档跑一次诊断所需的全部准备动作。
 *
 * <p>把"读文件 → 切分 → 对应测试集"这三步收在一起，是因为顺序不能乱：
 * 测试集必须等切分完才能对应到片段上（它按文字找片段，不写死编号）。
 *
 * <p>做成一个类而不是散在命令里，是为了让这套流程能被测试直接调用，
 * 不用去启动命令行。
 */
public final class CorpusPreparation {

    private final List<Document> documents;
    private final List<Chunk> chunks;
    private final GoldenCaseSetSpec caseSpec;
    private final String sourceDescription;

    private CorpusPreparation(List<Document> documents,
                              List<Chunk> chunks,
                              GoldenCaseSetSpec caseSpec,
                              String sourceDescription) {
        this.documents = documents;
        this.chunks = chunks;
        this.caseSpec = caseSpec;
        this.sourceDescription = sourceDescription;
    }

    /**
     * 读目录、切分、读测试集文件。
     *
     * @param corpusDir     文档目录
     * @param evalFile      测试集文件
     * @param chunkSize     切分窗口大小
     * @param chunkOverlap  相邻窗口的重叠字符数
     */
    public static CorpusPreparation from(Path corpusDir, Path evalFile,
                                         int chunkSize, int chunkOverlap) {
        DocumentReader reader = new DirectoryDocumentReader(corpusDir);
        List<Document> documents = reader.readAll();
        if (documents.isEmpty()) {
            throw new IllegalStateException(
                    "目录里没有可读的文档：" + corpusDir.toAbsolutePath()
                            + "\n支持的文件类型：" + DirectoryDocumentReader.DEFAULT_EXTENSIONS);
        }

        ChunkingStrategy strategy = new RecursiveCharacterChunkingStrategy(chunkSize, chunkOverlap);
        List<Chunk> chunks = new ArrayList<>();
        for (Document document : documents) {
            chunks.addAll(strategy.chunk(document));
        }

        GoldenCaseSetSpec spec = EvalFileReader.read(evalFile);

        String description = String.format("%d 个文档（%s），切分后 %d 个片段",
                documents.size(), reader.describe(), chunks.size());
        return new CorpusPreparation(documents, List.copyOf(chunks), spec, description);
    }

    /** 用已经准备好的材料直接构造，供测试使用。 */
    public static CorpusPreparation of(List<Document> documents,
                                       List<Chunk> chunks,
                                       GoldenCaseSetSpec caseSpec,
                                       String sourceDescription) {
        return new CorpusPreparation(documents, chunks, caseSpec, sourceDescription);
    }

    public List<Document> documents() {
        return documents;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public GoldenCaseSetSpec caseSpec() {
        return caseSpec;
    }

    /** 语料来源描述，用于报告标题。 */
    public String sourceDescription() {
        return sourceDescription;
    }
}
