package cn.qiyec.ragsight.application;

import cn.qiyec.ragsight.domain.evaluation.EvaluationSet;
import cn.qiyec.ragsight.domain.knowledge.Chunk;

import java.util.List;

/**
 * 诊断请求——一次诊断实验的输入。
 *
 * <p>三个要素固定：<b>语料</b>（测什么知识库）、<b>评测集</b>（用什么标尺）、
 * <b>配置列表</b>（对比哪些检索方案）。
 *
 * <p>语料以 {@link Chunk} 列表形式传入而非文档——切分是上游步骤，
 * 这样"切分策略"本身也能成为实验变量：换一种切分策略会得到不同的
 * Chunk 列表，从而在同一套诊断流程里被对比（见 ADR-001 的两层设计）。
 *
 * @param corpusName 语料名称，用于报告标识
 * @param chunks     已切分的语料片段
 * @param evalSet    评测集
 * @param configs    要对比的检索配置，至少一个
 */
public record DiagnosisRequest(
        String corpusName,
        List<Chunk> chunks,
        EvaluationSet evalSet,
        List<RetrieverConfig> configs) {

    public DiagnosisRequest {
        if (corpusName == null || corpusName.isBlank()) {
            throw new IllegalArgumentException("语料名称不能为空");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("语料不能为空");
        }
        if (evalSet == null) {
            throw new IllegalArgumentException("评测集不能为空");
        }
        if (configs == null || configs.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个检索配置");
        }
        chunks = List.copyOf(chunks);
        configs = List.copyOf(configs);

        // 配置 id 重复会让报告无法区分行——这个约束与 EvaluationSet 维护
        // "用例 id 唯一" 同理，属于集合层面的不变式
        List<String> ids = configs.stream().map(RetrieverConfig::id).toList();
        if (ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("检索配置 id 重复，报告将无法区分：" + ids);
        }
    }

    /** 语料片段总数。 */
    public int corpusSize() {
        return chunks.size();
    }
}
