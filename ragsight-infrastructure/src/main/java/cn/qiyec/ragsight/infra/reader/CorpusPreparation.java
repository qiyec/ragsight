package cn.qiyec.ragsight.infra.reader;

import cn.qiyec.ragsight.application.GoldenCaseSetSpec;
import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.DocumentReader;

import java.nio.file.Path;
import java.util.List;

/**
 * 从磁盘上读一批文档和它的测试集。
 *
 * <p>只做"读"这件事，不做切分——切分现在是对比维度之一，
 * 由诊断流程按各个切分方案分别做（见 {@code DiagnosisEngine}）。
 *
 * <p>做成一个类而不是散在命令里，是为了让这套流程能被测试直接调用，
 * 不用去启动命令行。
 */
public final class CorpusPreparation {

    private final List<Document> documents;
    private final GoldenCaseSetSpec caseSpec;
    private final String sourceDescription;

    private CorpusPreparation(List<Document> documents,
                              GoldenCaseSetSpec caseSpec,
                              String sourceDescription) {
        this.documents = documents;
        this.caseSpec = caseSpec;
        this.sourceDescription = sourceDescription;
    }

    /**
     * 读文档目录和测试集文件。
     *
     * @param corpusDir 文档目录
     * @param evalFile  测试集文件
     */
    public static CorpusPreparation from(Path corpusDir, Path evalFile) {
        DocumentReader reader = new DirectoryDocumentReader(corpusDir);
        List<Document> documents = reader.readAll();
        if (documents.isEmpty()) {
            throw new IllegalStateException(
                    "目录里没有可读的文档：" + corpusDir.toAbsolutePath()
                            + "\n支持的文件类型：" + DirectoryDocumentReader.DEFAULT_EXTENSIONS);
        }

        GoldenCaseSetSpec spec = EvalFileReader.read(evalFile);

        String description = String.format("%d 个文档（%s）",
                documents.size(), reader.describe());
        return new CorpusPreparation(documents, spec, description);
    }

    /** 用已经准备好的材料直接构造，供测试使用。 */
    public static CorpusPreparation of(List<Document> documents,
                                       GoldenCaseSetSpec caseSpec,
                                       String sourceDescription) {
        return new CorpusPreparation(documents, caseSpec, sourceDescription);
    }

    public List<Document> documents() {
        return documents;
    }

    public GoldenCaseSetSpec caseSpec() {
        return caseSpec;
    }

    /** 语料来源描述，用于报告标题。 */
    public String sourceDescription() {
        return sourceDescription;
    }
}
