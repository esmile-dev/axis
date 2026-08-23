package com.esmile.axis.knowledge.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KnowledgeQaPrompts}: title/summary injection, truncation note,
 * and the no-summary case.
 */
class KnowledgeQaPromptsTest {

    @Test
    void build_injectsTitleSummaryAndContent() {
        String prompt = KnowledgeQaPrompts.qaSystemPrompt("标题", "## TL;DR\n总结", "正文");

        assertThat(prompt).contains("条目标题：标题");
        assertThat(prompt).contains("条目总结：\n## TL;DR\n总结");
        assertThat(prompt).contains("条目原文：\n正文");
        assertThat(prompt).contains("原文未提及");
    }

    @Test
    void build_blankSummary_omitsSummarySection() {
        String prompt = KnowledgeQaPrompts.qaSystemPrompt("标题", "  ", "正文");

        assertThat(prompt).doesNotContain("条目总结");
        assertThat(prompt).contains("条目标题：标题");
    }

    @Test
    void build_nullSummary_omitsSummarySection() {
        String prompt = KnowledgeQaPrompts.qaSystemPrompt("标题", null, "正文");

        assertThat(prompt).doesNotContain("条目总结");
    }

    @Test
    void build_shortContent_noTruncationNote() {
        String prompt = KnowledgeQaPrompts.qaSystemPrompt("标题", null, "正文");

        assertThat(prompt).doesNotContain("已截断");
    }

    @Test
    void build_longContent_truncatesWithNote() {
        String longContent = "x".repeat(KnowledgeQaPrompts.MAX_CONTENT_CHARS + 100);

        String prompt = KnowledgeQaPrompts.qaSystemPrompt("标题", null, longContent);

        assertThat(prompt).contains("已截断");
        assertThat(prompt).contains("x".repeat(KnowledgeQaPrompts.MAX_CONTENT_CHARS));
        assertThat(prompt).doesNotContain("x".repeat(KnowledgeQaPrompts.MAX_CONTENT_CHARS + 1));
    }
}
