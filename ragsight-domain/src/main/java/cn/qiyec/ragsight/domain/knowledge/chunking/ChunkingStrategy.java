package cn.qiyec.ragsight.domain.knowledge.chunking;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.Document;

import java.util.List;

/**
 * 切分策略（端口）。
 *
 * <p>RAG 流水线的第一道关卡，也是诊断中最常见的瓶颈来源之一：
 * 如果答案被切断在两个片段之间，无论后续检索和重排多强都无法召回完整答案。
 *
 * <p>本接口刻意设计为**无状态纯函数**：给定相同文档，必须产出完全相同的片段列表。
 * 这是诊断结论可复现的前提（见 ADR-001、AGENTS.md 铁律 R5）。
 *
 * <p>实现必须通过 {@link Document#toChunks(List)} 装配结果，
 * 以保证序号连续、空片段被剔除等不变式只在一处维护。
 */
public interface ChunkingStrategy {

    /**
     * 策略名称。用于报告、实验配置与消融对比，要求稳定且唯一。
     */
    String name();

    /**
     * 把文档切分为片段。
     *
     * @param document 待切分文档，非空
     * @return 片段列表；文档非空，因此返回值至少包含一个片段
     */
    List<Chunk> chunk(Document document);

    /**
     * 人类可读的参数描述，用于诊断报告。
     *
     * <p>同一个策略类配不同参数（如 512/128 与 256/64）是两个不同的实验，
     * 报告里必须能区分，因此名称不足以表达，需要参数描述。
     */
    String describe();
}
