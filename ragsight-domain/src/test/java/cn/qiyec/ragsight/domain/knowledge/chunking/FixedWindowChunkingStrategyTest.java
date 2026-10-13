package cn.qiyec.ragsight.domain.knowledge.chunking;

import cn.qiyec.ragsight.domain.knowledge.Chunk;
import cn.qiyec.ragsight.domain.knowledge.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("固定窗口切分策略")
class FixedWindowChunkingStrategyTest {

    private static Document doc(int length) {
        return Document.of("d1", "字".repeat(length));
    }

    @Test
    @DisplayName("构造参数非法时应立即失败，而不是等到切分时才出错")
    void should_reject_invalid_parameters() {
        assertThatThrownBy(() -> new FixedWindowChunkingStrategy(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSize 必须为正数");

        assertThatThrownBy(() -> new FixedWindowChunkingStrategy(100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap 不能为负数");

        // overlap >= windowSize 会让窗口无法前进，必须拒绝
        assertThatThrownBy(() -> new FixedWindowChunkingStrategy(100, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须小于 windowSize");
    }

    @Test
    @DisplayName("文档短于窗口时只产出一个片段，且不丢内容")
    void should_produce_single_chunk_when_document_shorter_than_window() {
        Document document = doc(50);
        List<Chunk> chunks = new FixedWindowChunkingStrategy(100, 20).chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(document.content());
        assertThat(chunks.get(0).ordinal()).isZero();
    }

    @Test
    @DisplayName("片段不应超过窗口大小")
    void should_never_exceed_window_size() {
        int window = 100;
        List<Chunk> chunks = new FixedWindowChunkingStrategy(window, 20).chunk(doc(300));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(window));
    }

    @Test
    @DisplayName("片段序号必须从 0 连续递增，且与 ChunkId 一致")
    void should_have_sequential_ordinals() {
        List<Chunk> chunks = new FixedWindowChunkingStrategy(100, 20).chunk(doc(300));

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).ordinal()).isEqualTo(i);
            assertThat(chunks.get(i).id().ordinal()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("最后一个片段必须覆盖到文档末尾，否则尾部内容永远检索不到")
    void should_cover_document_tail() {
        Document document = doc(250);
        List<Chunk> chunks = new FixedWindowChunkingStrategy(100, 20).chunk(document);

        String lastChunk = chunks.get(chunks.size() - 1).text();
        String tail = document.content().substring(document.content().length() - 10);
        assertThat(lastChunk).endsWith(tail);
    }

    @Test
    @DisplayName("相邻片段应存在重叠，用于跨边界答案的召回")
    void should_overlap_adjacent_chunks() {
        int window = 100;
        int overlap = 20;
        Document document = buildSequentialDocument(250);
        List<Chunk> chunks = new FixedWindowChunkingStrategy(window, overlap).chunk(document);

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).text();
            String expectedPrefix = previous.substring(previous.length() - overlap);
            assertThat(chunks.get(i).text()).startsWith(expectedPrefix);
        }
    }

    @Test
    @DisplayName("重叠为 0 时相邻片段不应有重复内容")
    void should_not_overlap_when_overlap_is_zero() {
        Document document = buildSequentialDocument(250);
        List<Chunk> chunks = new FixedWindowChunkingStrategy(100, 0).chunk(document);

        String merged = String.join("", chunks.stream().map(Chunk::text).toList());
        // 无重叠时拼接结果应恰好等于原文（document.toChunks 会 strip，构造时已无空白）
        assertThat(merged).isEqualTo(document.content());
    }

    @Test
    @DisplayName("相同输入必须产出完全相同结果（可复现性）")
    void should_be_deterministic() {
        Document document = buildSequentialDocument(500);
        FixedWindowChunkingStrategy strategy = new FixedWindowChunkingStrategy(120, 30);

        List<String> first = strategy.chunk(document).stream().map(Chunk::text).toList();
        List<String> second = strategy.chunk(document).stream().map(Chunk::text).toList();

        assertThat(first).isEqualTo(second);
    }

    /**
     * 构造可区分位置的文本，便于断言重叠区间。使用固定宽度的序号前缀。
     */
    private static Document buildSequentialDocument(int length) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < length) {
            sb.append(String.format("%05d", i++));
        }
        return Document.of("d1", sb.substring(0, length));
    }
}
