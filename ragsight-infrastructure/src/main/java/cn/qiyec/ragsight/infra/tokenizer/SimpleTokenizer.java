package cn.qiyec.ragsight.infra.tokenizer;

import cn.qiyec.ragsight.domain.retrieval.Tokenizer;

import java.util.List;

/**
 * 简易分词器：实现 {@link Tokenizer} 接口，委托给 {@link TextTokenizer}。
 *
 * <p>无外部依赖，因此可在 CI 与单元测试中直接使用（R4）。
 * 它同时也是与离线向量化共用的切词规则，保证两条召回路径的词元一致。
 *
 * <p>未来若接入专业中文分词器，新增一个实现即可，算法与测试都不需要改动
 * （见 ADR-004）。
 */
public final class SimpleTokenizer implements Tokenizer {

    @Override
    public String name() {
        return "simple-char-word";
    }

    @Override
    public List<String> tokenize(String text) {
        return TextTokenizer.tokenize(text);
    }
}
