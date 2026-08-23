package com.esmile.axis.knowledge.generate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KnowledgePrompts}: three-section summary structure,
 * mindmap constraints, truncation note, and code-fence stripping.
 */
class KnowledgePromptsTest {

    @Test
    void summaryPrompt_containsThreeSectionsAndInput() {
        String prompt = KnowledgePrompts.summaryPrompt("标题甲", "正文甲");

        assertThat(prompt).contains("## TL;DR", "## 要点", "## 关键洞察");
        assertThat(prompt).contains("技术编辑");
        assertThat(prompt).contains("禁止编造");
        assertThat(prompt).contains("标题甲").contains("正文甲");
        assertThat(prompt).doesNotContain("截断");
    }

    @Test
    void mindmapPrompt_containsConstraints() {
        String prompt = KnowledgePrompts.mindmapPrompt("标题乙", "正文乙");

        assertThat(prompt).contains("标题层级");
        assertThat(prompt).contains("中心主题");
        assertThat(prompt).contains("4 级");
        assertThat(prompt).contains("20 字");
        assertThat(prompt).contains("5-40");
        assertThat(prompt).contains("正文段落");
        assertThat(prompt).contains("标题乙").contains("正文乙");
    }

    @Test
    void summaryPrompt_overlongContent_truncatesAndNotes() {
        String content = "x".repeat(KnowledgePrompts.MAX_CONTENT_CHARS) + "TAIL-MARKER";

        String prompt = KnowledgePrompts.summaryPrompt("长文", content);

        assertThat(prompt).contains("已截断");
        assertThat(prompt).contains(String.valueOf(KnowledgePrompts.MAX_CONTENT_CHARS));
        assertThat(prompt).doesNotContain("TAIL-MARKER");
    }

    @Test
    void summaryPrompt_nullContentAndTitle_rendersEmpty() {
        String prompt = KnowledgePrompts.summaryPrompt(null, null);

        assertThat(prompt).contains("文章标题：").contains("文章正文：");
        assertThat(prompt).doesNotContain("null");
    }

    @Test
    void stripCodeFence_markdownFence_removed() {
        String raw = "```markdown\n## TL;DR\n内容\n```";

        assertThat(KnowledgePrompts.stripCodeFence(raw)).isEqualTo("## TL;DR\n内容");
    }

    @Test
    void stripCodeFence_plainFence_removed() {
        String raw = "```\n# 脑图\n## 分支\n```";

        assertThat(KnowledgePrompts.stripCodeFence(raw)).isEqualTo("# 脑图\n## 分支");
    }

    @Test
    void stripCodeFence_noFence_unchanged() {
        String raw = "## TL;DR\n内容";

        assertThat(KnowledgePrompts.stripCodeFence(raw)).isEqualTo(raw);
    }

    @Test
    void stripCodeFence_null_returnsEmpty() {
        assertThat(KnowledgePrompts.stripCodeFence(null)).isEmpty();
    }
}
