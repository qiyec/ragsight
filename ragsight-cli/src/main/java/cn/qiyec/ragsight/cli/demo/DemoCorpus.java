package cn.qiyec.ragsight.cli.demo;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.ChunkId;
import cn.qiyec.ragsight.domain.knowledge.Document;
import cn.qiyec.ragsight.domain.knowledge.DocumentId;
import cn.qiyec.ragsight.domain.knowledge.chunking.ChunkingStrategy;
import cn.qiyec.ragsight.domain.knowledge.chunking.RecursiveCharacterChunkingStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置演示语料——一个虚构的 SaaS 产品帮助中心。
 *
 * <p><b>为什么需要内置语料</b>：让 `rag diagnose` 在 clone 之后无需任何
 * 外部数据即可跑出真实诊断报告。没有它，用户看到的是一个"需要先准备
 * 语料和评测集"的空工具，无法在 5 分钟内理解它能做什么。
 *
 * <p>语料刻意设计成包含**多种检索难点**，使诊断报告有内容可讲：
 * <ul>
 *   <li>精确编号（错误码、SKU）—— 向量路的弱项，关键词路的强项</li>
 *   <li>同义改写查询 —— 关键词路的弱项</li>
 *   <li>分散在多段的信息 —— 需要足够召回量</li>
 * </ul>
 *
 * <p>这是**演示数据**，不是真实产品文档。指标仅用于说明工具的输出形态，
 * 不代表任何真实系统的表现。
 */
public final class DemoCorpus {

    private DemoCorpus() {
    }

    /** 演示文档。每篇对应帮助中心的一个主题。 */
    private static List<Document> documents() {
        return List.of(
                Document.of("billing-rules",
                        """
                        计费规则说明

                        本产品采用按量计费模式，费用在每个自然月结束时结算。
                        账单会在次月 3 日前生成，可通过控制台的账单中心查看。

                        计费项包括三部分：计算资源、存储空间和网络流量。
                        计算资源按实例运行时长计费，不足一分钟按一分钟计算。
                        存储空间按实际占用量计费，每日采样一次取当月峰值。
                        网络流量按出站流量计费，入站流量免费。

                        如需调整计费方式，请在账单中心提交申请，审核通过后次月生效。
                        """),
                Document.of("billing-error",
                        """
                        账单异常处理

                        当账单出现异常时，请先检查以下常见原因：
                        账户余额不足会导致实例自动停机，恢复余额后需手动重启实例。
                        计费项突增通常是因为存储空间峰值上涨或出站流量增加。

                        若确认账单金额与预期不符，可通过工单系统提交申诉。
                        申诉时需要提供账单编号和异常时间段的实例列表。

                        常见的账单相关错误码：
                        错误码 E4021 表示计费账户被冻结，需要联系客户经理解冻。
                        错误码 E4022 表示账单数据尚未生成，通常在每月 3 日后自动解除。
                        错误码 E4023 表示计费项配置冲突，需要在控制台重新确认计费方案。
                        """),
                Document.of("auth-flow",
                        """
                        用户登录与鉴权

                        系统支持三种登录方式：账号密码、企业单点登录和 API 密钥。
                        账号密码登录需要绑定手机号用于二次验证。

                        企业单点登录基于标准协议实现，需要在管理后台配置身份提供方信息。
                        配置完成后，企业成员可使用内部账号直接登录，无需单独注册。

                        登录态默认有效期为 8 小时，过期后需要重新登录。
                        连续输错密码 5 次会锁定账号 30 分钟。

                        错误码 E1001 表示用户名或密码错误。
                        错误码 E1002 表示账号已被锁定。
                        错误码 E1003 表示单点登录配置无效。
                        """),
                Document.of("storage-quota",
                        """
                        存储配额管理

                        每个账户的存储配额默认 100 GB，可根据套餐升级。
                        存储用量每日凌晨统计一次，控制台显示的用量有一天的延迟。

                        当用量达到配额的 90% 时，系统会发送预警通知。
                        达到 100% 后将无法写入新数据，已存在的数据仍可正常读取。

                        如需临时扩容，可在控制台提交扩容申请，通常 10 分钟内生效。
                        永久扩容需要变更套餐，在下一个计费周期生效。
                        """),
                Document.of("release-notes",
                        """
                        版本发布记录

                        本页面记录产品的功能更新与变更。

                        近期更新包括控制台界面改版、账单导出功能上线、
                        以及存储配额预警通知的优化。具体版本号与时间请参见控制台公告。
                        """));
    }

    /**
     * 对演示文档执行切分，得到语料片段。
     *
     * <p><b>切分参数的选取对演示效果至关重要</b>：
     * 若窗口过大（如 300），每篇文档只会切出 1 个片段——语料总量与 topK 相当，
     * 等于"每次都返回全部内容"，Recall 恒为 1.0，诊断就失去意义。
     * 因此这里取较小的窗口（90 字符），使每篇文档切成多个片段，
     * 让"能否召回正确片段"成为真实的挑战。
     *
     * <p>这个教训本身也是诊断工具要传达的东西：<b>切分粒度过大会让检索
     * 看起来"永远正确"</b>，直到语料增长后才暴露出问题。
     *
     * @return 可直接送入诊断引擎的片段列表
     */
    public static List<Chunk> chunked() {
        ChunkingStrategy strategy = new RecursiveCharacterChunkingStrategy(90, 18);
        List<Chunk> all = new ArrayList<>();
        for (Document document : documents()) {
            all.addAll(strategy.chunk(document));
        }
        return List.copyOf(all);
    }

    /** 语料名称，用于报告标题。 */
    public static String corpusName() {
        return "演示语料：SaaS 产品帮助中心（5 篇文档）";
    }

    /**
     * 供归因分析：按片段 id 找到片段文本。
     *
     * <p>演示场景下评测集直接用片段 id 标注，
     * 因此需要能反查到文本以便在报告中展示命中的证据内容。
     */
    public static String textOf(List<Chunk> corpus, String chunkId) {
        return corpus.stream()
                .filter(c -> c.id().toString().equals(chunkId))
                .map(Chunk::text)
                .findFirst()
                .orElse("(未找到)");
    }

    /** 供评测集标注使用：构造片段 id。 */
    public static ChunkId id(String docId, int ordinal) {
        return new ChunkId(DocumentId.of(docId), ordinal);
    }
}
