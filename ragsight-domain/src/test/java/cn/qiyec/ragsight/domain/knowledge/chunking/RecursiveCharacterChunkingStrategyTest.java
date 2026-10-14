package cn.qiyec.ragsight.domain.knowledge.chunking;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("递归字符切分策略")
class RecursiveCharacterChunkingStrategyTest {

    @Test
    @DisplayName("构造参数非法时应立即失败")
    void should_reject_invalid_parameters() {
        assertThatThrownBy(() -> new RecursiveCharacterChunkingStrategy(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkSize 必须为正数");

        assertThatThrownBy(() -> new RecursiveCharacterChunkingStrategy(100, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须小于 chunkSize");
    }

    @Test
    @DisplayName("优先在段落边界切分，片段应包含完整句子")
    void should_prefer_paragraph_boundary() {
        String paragraphA = "第一段内容。这句话描述计费规则的第一条。";
        String paragraphB = "第二段内容。这句话描述计费规则的第二天。";
        Document document = Document.of("d1", paragraphA + "\n\n" + paragraphB);

        // 窗口大于单段、小于两段之和，应恰好切出两段而不是在句子中间硬切
        List<Chunk> chunks = new RecursiveCharacterChunkingStrategy(paragraphA.length() + 5, 0)
                .chunk(document);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo(paragraphA);
        assertThat(chunks.get(1).text()).isEqualTo(paragraphB);
    }

    @Test
    @DisplayName("片段不应超过 chunkSize（单段本身超长时由递归降级处理）")
    void should_respect_chunk_size() {
        String text = "这是一句测试文本。".repeat(50);
        int chunkSize = 100;
        List<Chunk> chunks = new RecursiveCharacterChunkingStrategy(chunkSize, 0).chunk(Document.of("d1", text));

        assertThat(chunks).isNotEmpty();
        // 允许最后一块因重叠机制略大，此处 overlap=0，故必须全部不超限
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(chunkSize));
    }

    @Test
    @DisplayName("无任何分隔符的连续文本不应死循环，应退化为硬切")
    void should_fallback_to_hard_split_without_separators() {
        String noSeparatorText = "字".repeat(500);
        int chunkSize = 100;

        List<Chunk> chunks = new RecursiveCharacterChunkingStrategy(chunkSize, 0)
                .chunk(Document.of("d1", noSeparatorText));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(chunkSize));
        // 硬切后拼接应还原原文，证明没丢字符
        String merged = String.join("", chunks.stream().map(Chunk::text).toList());
        assertThat(merged).isEqualTo(noSeparatorText);
    }

    @Test
    @DisplayName("短片段应被合并，避免产出大量信息量不足的碎片")
    void should_merge_small_pieces() {
        // 20 个很短的段落，窗口足够容纳多个
        String text = String.join("\n\n", java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "短段落" + i)
                .toList());
        Document document = Document.of("d1", text);

        List<Chunk> chunks = new RecursiveCharacterChunkingStrategy(200, 0).chunk(document);

        // 若不合并会产生 20 个片段；窗口 200 应把它们合并成远少于 20 个
        assertThat(chunks.size()).isLessThan(20);
    }

    @Test
    @DisplayName("序号必须从 0 连续递增")
    void should_have_sequential_ordinals() {
        Document document = Document.of("d1", "段落一。\n\n段落二。\n\n段落三。");
        List<Chunk> chunks = new RecursiveCharacterChunkingStrategy(10, 2).chunk(document);

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).ordinal()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("含超长片段时，片段顺序必须与原文一致（顺序规则）")
    void should_preserve_order_when_piece_exceeds_chunk_size() {
        // 构造：超长无分隔符段落 + 短段落。
        // 超长段落会被硬切，短段落本身不超限——两者混合时最容易触发顺序错乱。
        String longParagraph = "字".repeat(300);
        String shortParagraph = "这是紧跟在超长段落之后的短段落，它必须排在超长段落的片段之后。";
        Document document = Document.of("d1", longParagraph + "\n\n" + shortParagraph);

        List<Chunk> chunks = new RecursiveCharacterChunkingStrategy(100, 0).chunk(document);

        // 片段按序拼接应还原原文的**非空白内容**。
        // 注意：不能直接与 document.content() 比较——段落间的 "\n\n" 是分隔符，
        // 不属于任何片段（片段首尾空白会被 Document.toChunks 剔除）。
        String merged = chunks.stream().map(Chunk::text).reduce("", String::concat);
        assertThat(merged).isEqualTo(longParagraph + shortParagraph);

        // 顺序规则的关键断言：短段落必须在最后，不能被插到超长段落的片段之前
        assertThat(chunks.get(chunks.size() - 1).text()).isEqualTo(shortParagraph);

        // 超长段落应被切成多个不超限的片段，而不是整块塞进一个片段
        assertThat(chunks).hasSize(4);
        assertThat(chunks.subList(0, 3)).allSatisfy(c -> assertThat(c.length()).isEqualTo(100));
    }

    @Test
    @DisplayName("相同输入必须产出完全相同结果（可复现性）")
    void should_be_deterministic() {
        Document document = Document.of("d1", "第一句。第二句。第三句。\n\n第二段。".repeat(20));
        RecursiveCharacterChunkingStrategy strategy = new RecursiveCharacterChunkingStrategy(120, 20);

        List<String> first = strategy.chunk(document).stream().map(Chunk::text).toList();
        List<String> second = strategy.chunk(document).stream().map(Chunk::text).toList();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("describe 应包含可区分的参数，供消融实验报告标识不同配置")
    void should_describe_parameters() {
        assertThat(new RecursiveCharacterChunkingStrategy(512, 64).describe())
                .contains("512").contains("64");
        assertThat(new RecursiveCharacterChunkingStrategy(256, 32).describe())
                .isNotEqualTo(new RecursiveCharacterChunkingStrategy(512, 64).describe());
    }
}
