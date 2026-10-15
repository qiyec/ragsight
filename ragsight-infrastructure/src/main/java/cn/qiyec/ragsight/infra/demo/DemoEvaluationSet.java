package cn.qiyec.ragsight.infra.demo;

import cn.qiyec.ragsight.application.GoldenCaseSetSpec;
import cn.qiyec.ragsight.application.GoldenCaseSpec;

import java.util.List;

/**
 * 内置演示测试集——故意设计成能暴露不同召回方式的弱点。
 *
 * <p><b>为什么标注用"关键文字"而不是片段编号</b>：
 * 片段编号形如 {@code billing-error#2}，那个 2 取决于切分参数。
 * 写死之后一调切分就会指向不存在的片段，指标全变 0，
 * 而你多半会以为是检索坏了。用关键文字就没这个问题——
 * 切分怎么变都能重新找到对应片段。
 *
 * <p><b>用例为什么这样挑</b>：诊断的价值在于区分不同的失败模式，
 * 所以测试集必须包含能区分它们的问题：
 * <ul>
 *   <li>精确编号类（错误码）——向量召回容易漏，关键词召回占优</li>
 *   <li>同义改写类——问题用词和文档不完全一致</li>
 *   <li>负样本——检验检索器会不会硬凑一个答案</li>
 * </ul>
 * 如果全是"和文档用词一模一样"的问题，各种召回方式表现会完全一样，
 * 报告也就给不出任何建议。
 */
public final class DemoEvaluationSet {

    private DemoEvaluationSet() {
    }

    /** 构造演示测试集。 */
    public static GoldenCaseSetSpec build() {
        return new GoldenCaseSetSpec("演示测试集", List.of(
                // 精确编号类。特征文字刻意取短：长句子容易因为换行和空格的
                // 细微差异对不上，短文字更稳
                spec("c001", "错误码 E4021 是什么意思", "E4021 表示"),
                spec("c002", "E1002 错误怎么处理", "E1002 表示"),
                spec("c003", "错误码 E4023 代表什么", "E4023 表示"),

                // 同义改写类：问题用词和文档不完全一致
                spec("c004", "账单什么时候出", "次月 3 日前生成"),
                spec("c005", "密码输错太多次会怎样", "锁定账号 30 分钟"),
                spec("c006", "存储快满的时候有提醒吗", "达到配额的"),
                spec("c007", "出站流量怎么收费", "出站流量计费"),
                spec("c008", "单点登录怎么配", "配置身份提供方信息"),

                // 负样本：知识库里没有答案，检验检索器会不会硬凑一个出来
                new GoldenCaseSpec("c009", "手机客户端支持指纹登录吗", List.of()),
                new GoldenCaseSpec("c010", "如何申请退款", List.of())));
    }

    private static GoldenCaseSpec spec(String id, String question, String marker) {
        return new GoldenCaseSpec(id, question, List.of(marker));
    }

    /** 报告里用的说明文字。 */
    public static String description() {
        return "8 个正样本（含精确编号与同义改写两类）+ 2 个负样本";
    }
}
