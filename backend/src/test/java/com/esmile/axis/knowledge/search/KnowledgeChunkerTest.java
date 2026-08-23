package com.esmile.axis.knowledge.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnowledgeChunker} 新契约（knowledge-chunking lite-spec）：
 * 清洗 → 递归结构切分（≤500 token）→ 标题前置；边界落句末/段落，重叠为前块尾部整句。
 */
class KnowledgeChunkerTest {

    private static final String TITLE = "缓存更新的套路";
    private static final String PREFIX = TITLE + "\n\n";

    /** 生成 n 句互不相同的中文句（每句 ~20 token），句间无换行（最不利于切分的形态）。 */
    private static String sentences(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("第").append(i).append("句讨论缓存一致性的实现细节与工程权衡。");
        }
        return sb.toString();
    }

    /** 去掉标题前缀，只留块体（标题为空时原样返回）。 */
    private static List<String> bodies(List<String> chunks) {
        return chunks.stream()
                .map(c -> c.startsWith(PREFIX) ? c.substring(PREFIX.length()) : c)
                .toList();
    }

    @Test
    void chunk_nullOrBlank_returnsEmpty() {
        assertThat(KnowledgeChunker.chunk(TITLE, null)).isEmpty();
        assertThat(KnowledgeChunker.chunk(TITLE, "")).isEmpty();
        assertThat(KnowledgeChunker.chunk(TITLE, "   \n ")).isEmpty();
    }

    @Test
    void chunk_allNoiseContent_returnsEmpty() {
        // 纯图片 + 锚点残留的条目，清洗后无实义内容，不切块
        String noise = "![](https://cdn.x/1.png)\n\n![](https://cdn.x/2.png)\n{#page-title}\n";
        assertThat(KnowledgeChunker.chunk(TITLE, noise)).isEmpty();
    }

    @Test
    void chunk_shortText_singleChunkWithTitlePrefix() {
        assertThat(KnowledgeChunker.chunk(TITLE, "先更新数据库，再删缓存。"))
                .containsExactly(PREFIX + "先更新数据库，再删缓存。");
    }

    @Test
    void chunk_blankTitle_noPrefix() {
        assertThat(KnowledgeChunker.chunk(null, "正文内容。")).containsExactly("正文内容。");
        assertThat(KnowledgeChunker.chunk("  ", "正文内容。")).containsExactly("正文内容。");
    }

    @Test
    void chunk_cleansImageLinkAnchorComment() {
        String md = "[缓存套路](https://x.cn/a) 与 ![](https://x.cn/i.png) 图 {#more-1} <!-- 注释 --> 完";
        List<String> chunks = KnowledgeChunker.chunk(null, md);
        assertThat(chunks).hasSize(1);
        String body = chunks.get(0);
        assertThat(body).contains("缓存套路").contains("图").contains("完");
        assertThat(body).doesNotContain("https://", "![", "{#", "<!--");
    }

    @Test
    void chunk_collapseBlankRunsLeftByCleaning() {
        String md = "第一段。\n\n![](https://x.cn/i.png)\n\n\n第二段。";
        assertThat(KnowledgeChunker.chunk(null, md)).containsExactly("第一段。\n\n第二段。");
    }

    @Test
    void chunk_longText_withinTokenBudget() {
        List<String> bodies = bodies(KnowledgeChunker.chunk(TITLE, sentences(120)));
        assertThat(bodies.size()).isGreaterThan(1);
        for (String body : bodies) {
            assertThat(KnowledgeChunker.tokenCount(body)).isLessThanOrEqualTo(KnowledgeChunker.CHUNK_TOKENS);
        }
    }

    @Test
    void chunk_longText_boundariesAtSentenceEnds() {
        String content = sentences(120);
        for (String body : bodies(KnowledgeChunker.chunk(TITLE, content))) {
            int pos = content.indexOf(body);
            assertThat(pos).as("块体必须在原文中可定位").isGreaterThanOrEqualTo(0);
            if (pos > 0) {
                assertThat(content.charAt(pos - 1)).as("块边界必须落在句末/段落，不得断句").isIn('。', '！', '？', '\n');
            }
        }
    }

    @Test
    void chunk_longText_overlapCarriesTailSentences() {
        List<String> bodies = bodies(KnowledgeChunker.chunk(TITLE, sentences(120)));
        for (int i = 1; i < bodies.size(); i++) {
            String firstSentence = bodies.get(i).substring(0, bodies.get(i).indexOf('。') + 1);
            assertThat(bodies.get(i - 1)).as("重叠应把前块尾部整句带入下一块").contains(firstSentence);
        }
    }

    @Test
    void chunk_longText_noSentenceLost() {
        String content = sentences(120);
        List<String> bodies = bodies(KnowledgeChunker.chunk(TITLE, content));
        for (String sentence : content.split("(?<=。)")) {
            assertThat(bodies.stream().anyMatch(b -> b.contains(sentence)))
                    .as("句子不丢失：%s", sentence).isTrue();
        }
    }

    @Test
    void chunk_noSeparator_hardCutWithinBudget() {
        List<String> chunks = KnowledgeChunker.chunk(null, "a".repeat(20_000));
        assertThat(chunks.size()).isGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(KnowledgeChunker.tokenCount(chunk)).isLessThanOrEqualTo(KnowledgeChunker.CHUNK_TOKENS);
        }
    }
}
