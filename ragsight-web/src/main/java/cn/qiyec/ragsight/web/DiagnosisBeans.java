package cn.qiyec.ragsight.web;

import cn.qiyec.ragsight.diagnosis.CorpusCatalog;
import cn.qiyec.ragsight.diagnosis.DiagnosisService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把共用模块里的东西接成 Spring Bean。
 *
 * <p>为什么不直接在 {@link CorpusCatalog} 上加 {@code @Service}：
 * 因为诊断 Agent 也要用它，而 Agent 那边（至少现在）不走 Spring 容器。
 * 让共用模块只留纯 Java 类，由各自的入口决定怎么装配——
 * web 用 Spring 装，agent 直接 new。共用模块因此不用认识任何框架。
 */
@Configuration
public class DiagnosisBeans {

    @Bean
    public CorpusCatalog corpusCatalog(
            @Value("${ragsight.examples-dir:examples}") String examplesDir) {
        return new CorpusCatalog(examplesDir);
    }

    @Bean
    public DiagnosisService diagnosisService(CorpusCatalog corpusCatalog) {
        return new DiagnosisService(corpusCatalog);
    }
}
