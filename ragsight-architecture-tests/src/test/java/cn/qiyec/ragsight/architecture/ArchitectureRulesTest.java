package cn.qiyec.ragsight.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构约束的自动化守护。
 *
 * <p>这些规则把 {@code AGENTS.md} 里的规矩从"文档承诺"变成"构建门禁"。
 * 违反即 {@code mvn verify} 失败，不依赖 code review 时的人为自觉。
 *
 * <p>覆盖的规矩：
 * <ul>
 *   <li>R1 —— domain 层零第三方运行时依赖</li>
 *   <li>R2 —— application 层只能依赖 domain</li>
 *   <li>R3 —— 需要外部能力的地方在 domain 定接口，实现放 infrastructure</li>
 *   <li>R5 —— domain 层不得引入随机性（否则诊断结果不可复现）</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "cn.qiyec.ragsight",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String DOMAIN = "cn.qiyec.ragsight.domain..";
    private static final String APPLICATION = "cn.qiyec.ragsight.application..";

    // ---------------------------------------------------------------
    // R1：domain 层零第三方运行时依赖
    // ---------------------------------------------------------------

    /**
     * <b>本项目最重要的一条架构约束。</b>
     *
     * <p>domain 只允许引用 JDK 自身与 domain 包内的类型。这保证了
     * 核心 RAG 算法与指标计算能在无网络、无数据库、无 API Key 的
     * 环境下完整单测——这是诊断结论可信的前提。
     *
     * <p>一旦有人为了省事在 domain 里 import 了 Jackson / Lombok /
     * 某个向量库 SDK，这条规则会立刻让构建失败。
     */
    @ArchTest
    static final ArchRule domain_不应依赖任何第三方库 =
            classes().that().resideInAPackage(DOMAIN)
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", DOMAIN);

    /**
     * 显式禁止几个最容易被"顺手引入"的库，让失败信息更直观。
     *
     * <p>与上一条规则有重叠，但这条的报错信息会直接点名是哪个库，
     * 比"依赖了不在允许范围内的类"更容易定位。
     */
    @ArchTest
    static final ArchRule domain_不应依赖常见便利库 =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.fasterxml.jackson..",
                            "org.projectlombok..",
                            "com.google.common..",
                            "org.springframework..",
                            "org.slf4j..",
                            "jakarta..",
                            "io.agentscope..");

    // ---------------------------------------------------------------
    // R2：application 层只能依赖 domain
    // ---------------------------------------------------------------

    /**
     * 用例层不得知道具体的外部实现（向量库、模型客户端、文件格式）。
     * 外部能力必须以 domain 定义的接口出现，实现放在 infrastructure。
     */
    @ArchTest
    static final ArchRule application_只能依赖_domain =
            classes().that().resideInAPackage(APPLICATION)
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", DOMAIN, APPLICATION);

    // ---------------------------------------------------------------
    // R5：domain 层不得引入随机性
    // ---------------------------------------------------------------

    /**
     * 诊断结果必须可复现：同样的输入、同样的配置，必须得到同样的数字。
     * 一旦 domain 层出现 {@code Math.random()} 或 {@code java.util.Random}，
     * 结论就无法被验证，工具失去可信度。
     */
    @ArchTest
    static final ArchRule domain_不应使用随机数 =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("java.util.Random", "java.security.SecureRandom");

    // ---------------------------------------------------------------
    // R3：接口在 domain，实现不得反向污染
    // ---------------------------------------------------------------

    /**
     * 基础设施层不得被 domain 或 application 反向依赖。
     *
     * <p>依赖方向必须是 infrastructure → domain，绝不能反过来。
     * 这条规则守护依赖方向。
     */
    @ArchTest
    static final ArchRule domain_不应依赖基础设施 =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.qiyec.ragsight.infra..", "cn.qiyec.ragsight.cli..");

    // ---------------------------------------------------------------
    // Web 层的边界（ADR-005 引入 Spring Boot 时定的）
    // ---------------------------------------------------------------

    /**
     * 用例层不许依赖 Web 层。
     *
     * <p>Web 是"驱动适配器"，处在最外层。cli 和 web 是两个平行的入口，
     * 谁都不该依赖谁。如果 application 反过来依赖 web，
     * 那就意味着"诊断逻辑需要知道有网页这回事"——这显然是错的。
     */
    @ArchTest
    static final ArchRule application_与_domain_不应依赖_web =
            noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.qiyec.ragsight.web..");

    /**
     * 用例层不许依赖 Spring。
     *
     * <p>引入 Spring Boot 是为了 Web 界面和将来的 Agent 层。
     * 但它绝不能渗进用例层——一旦渗进去，
     * "诊断流程能脱离框架被完整测试"这条就保不住了。
     */
    @ArchTest
    static final ArchRule application_不应依赖_spring =
            noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..");

    // ---------------------------------------------------------------
    // Agent 层的边界（第二层，会调大模型）
    // ---------------------------------------------------------------

    /**
     * 核心三层都不许依赖 Agent 层。
     *
     * <p>Agent 层是第二层，它是"会调大模型的那一层"。
     * 如果 domain 或 application 依赖了它，就等于诊断逻辑沾上了大模型——
     * 那"结论可复现"这个立身之本就没了。
     *
     * <p>方向必须是单向的：agent → web → infrastructure → application → domain。
     */
    @ArchTest
    static final ArchRule 核心层不应依赖_agent_层 =
            noClasses().that().resideInAnyPackage(
                            DOMAIN, APPLICATION, "cn.qiyec.ragsight.infra..",
                            "cn.qiyec.ragsight.cli..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.qiyec.ragsight.agent..");

    /**
     * 核心三层不许依赖 AgentScope。
     *
     * <p>AgentScope 是第二层的实现手段。它不该渗进第一层——
     * 一旦渗进去，整个项目就变成"必须先配好大模型才能跑"，
     * 违反"默认流程零配置可用"这条规矩。
     */
    @ArchTest
    static final ArchRule 核心层不应依赖_agentscope =
            noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.agentscope..");

    /**
     * Agent 层不许依赖命令行入口。
     *
     * <p>cli 和 agent 是两个平级的入口，谁都不该依赖谁。
     * Agent 层要用的是 diagnosis 那边的编排能力，不是命令行的参数解析。
     */
    @ArchTest
    static final ArchRule agent_层不应依赖_cli =
            noClasses().that().resideInAnyPackage("cn.qiyec.ragsight.agent..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.qiyec.ragsight.cli..");

    /**
     * Agent 层不许依赖网页。
     *
     * <p>方向是 <b>web → agent</b>，反过来不行。
     * 网页上有个"让 Agent 自己找瓶颈"的入口，所以 web 依赖 agent 是对的；
     * 但 agent 不能依赖 web——那会绕成一个环，而且 web 打出来的是
     * Spring Boot 应用，agent 依赖它意味着一堆用不上的东西。
     *
     * <p>两者共用的东西在 diagnosis 模块，不在 web 里。
     */
    @ArchTest
    static final ArchRule agent_层不应依赖_web =
            noClasses().that().resideInAnyPackage("cn.qiyec.ragsight.agent..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("cn.qiyec.ragsight.web..");

    /**
     * 共用编排层不许依赖 Agent 层，也不许认识 Spring。
     *
     * <p>diagnosis 是网页和 Agent 都要用的那段编排。它必须保持"纯 Java"：
     * 一旦它依赖了 Agent，网页就会间接依赖大模型；
     * 一旦它依赖了 Spring，Agent 那边不用 Spring 就直接构建不了。
     */
    @ArchTest
    static final ArchRule diagnosis_层应保持中立 =
            noClasses().that().resideInAnyPackage("cn.qiyec.ragsight.diagnosis..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "cn.qiyec.ragsight.agent..",
                            "cn.qiyec.ragsight.web..",
                            "cn.qiyec.ragsight.cli..",
                            "org.springframework..");
}
