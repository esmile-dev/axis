package com.esmile.axis.knowledge.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link KnowledgeChunker} 边界：空/短文本、刚好 1000、跨块重叠正确性。 */
class KnowledgeChunkerTest {

    @Test
    void chunk_nullOrBlank_returnsEmpty() {
        assertThat(KnowledgeChunker.chunk(null)).isEmpty();
        assertThat(KnowledgeChunker.chunk("")).isEmpty();
        assertThat(KnowledgeChunker.chunk("   \n ")).isEmpty();
    }

    @Test
    void chunk_shortText_singleChunk() {
        List<String> chunks = KnowledgeChunker.chunk("hello world");
        assertThat(chunks).containsExactly("hello world");
    }

    @Test
    void chunk_exactlyChunkSize_singleChunk() {
        String text = "a".repeat(KnowledgeChunker.CHUNK_SIZE);
        assertThat(KnowledgeChunker.chunk(text)).containsExactly(text);
    }

    @Test
    void chunk_oneOverChunkSize_twoChunksWithOverlap() {
        String text = "a".repeat(KnowledgeChunker.CHUNK_SIZE + 1);
        List<String> chunks = KnowledgeChunker.chunk(text);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(KnowledgeChunker.CHUNK_SIZE);
        // 第二块从 900 开始（重叠 100），含第一块末尾 100 个字符 + 最后 1 个
        assertThat(chunks.get(1)).isEqualTo(text.substring(KnowledgeChunker.CHUNK_SIZE - KnowledgeChunker.OVERLAP));
    }

    @Test
    void chunk_longText_overlapBoundariesAlign() {
        String text = "x".repeat(1000 + 900 + 500); // 3 块：1000 / 1000 / 500
        List<String> chunks = KnowledgeChunker.chunk(text);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(1000);
        assertThat(chunks.get(1)).hasSize(1000);
        assertThat(chunks.get(2)).hasSize(500 + KnowledgeChunker.OVERLAP);
        for (int i = 1; i < chunks.size(); i++) {
            // 每块起始 = i * (1000 - 100)
            assertThat(chunks.get(i)).isEqualTo(
                    text.substring(i * 900, Math.min(i * 900 + 1000, text.length())));
        }
    }

    @Test
    void chunk_sliverTailWithinOverlap_notDuplicated() {
        // 1850 = 1000 + 900 - 50：起点 1800 的尾巴仅 50 字符，已含于第二块重叠区
        String text = "x".repeat(1850);
        List<String> chunks = KnowledgeChunker.chunk(text);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1)).isEqualTo(text.substring(900));
    }

    @Test
    void chunk_contentPreservedAcrossChunks() {
        // 可区分内容验证切块不丢字：首块头 + 每块重叠区 + 末块尾拼接回原文
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2600; i++) {
            sb.append((char) ('a' + i % 26));
        }
        String text = sb.toString();
        List<String> chunks = KnowledgeChunker.chunk(text);
        StringBuilder rebuilt = new StringBuilder(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            rebuilt.append(chunks.get(i).substring(KnowledgeChunker.OVERLAP));
        }
        assertThat(rebuilt.toString()).isEqualTo(text);
    }
}
