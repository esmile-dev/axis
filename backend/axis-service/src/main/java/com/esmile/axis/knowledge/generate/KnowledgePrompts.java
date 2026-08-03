package com.esmile.axis.knowledge.generate;

/**
 * Prompt contract for knowledge artifact generation (design.md §6).
 * Constants are public so the T-006 evaluation harness can reuse them.
 */
public final class KnowledgePrompts {

    /** Inputs longer than this are truncated before being sent to the LLM. */
    public static final int MAX_CONTENT_CHARS = 100_000;

    public static final String PROMPT_SUMMARY = """
            你是资深技术编辑。阅读以下文章，输出固定三节的 Markdown 总结。

            输出要求：
            - 只输出三节，标题与顺序固定为：
              ## TL;DR
              不超过 3 句话概括全文。
              ## 要点
              3-7 条要点，每条一行，以 "- " 开头。
              ## 关键洞察
              1-3 条关键洞察，每条一行，以 "- " 开头。
            - 语言跟随原文（中文优先）。
            - 只依据原文，禁止编造原文没有的事实、数据或结论。
            - 不要输出三节以外的内容，不要用代码块包裹输出。

            文章标题：{title}
            {truncation}
            文章正文：
            {content}
            """;

    public static final String PROMPT_MINDMAP = """
            你是资深技术编辑。阅读以下文章，输出其脑图大纲。

            输出要求：
            - 仅输出 Markdown 标题层级（# / ## / ### / ####），不输出任何正文段落。
            - 一级标题（#）是中心主题，即文章主题。
            - 层级不超过 4 级。
            - 每个节点为短句，不超过 20 字。
            - 节点总数 5-40 个。
            - 语言跟随原文（中文优先）。
            - 不要用代码块包裹输出。

            文章标题：{title}
            {truncation}
            文章正文：
            {content}
            """;

    private static final String TRUNCATION_NOTE =
            "（注：原文内容过长已截断，以下仅基于前 " + MAX_CONTENT_CHARS + " 字符生成）";

    private KnowledgePrompts() {
    }

    public static String summaryPrompt(String title, String content) {
        return build(PROMPT_SUMMARY, title, content);
    }

    public static String mindmapPrompt(String title, String content) {
        return build(PROMPT_MINDMAP, title, content);
    }

    private static String build(String template, String title, String content) {
        String body = content == null ? "" : content;
        String note = "";
        if (body.length() > MAX_CONTENT_CHARS) {
            body = body.substring(0, MAX_CONTENT_CHARS);
            note = TRUNCATION_NOTE;
        }
        return template
                .replace("{title}", title == null ? "" : title)
                .replace("{truncation}", note)
                .replace("{content}", body);
    }

    /** LLMs sometimes wrap Markdown in a ``` fence; strip it before persisting. */
    public static String stripCodeFence(String raw) {
        String cleaned = raw == null ? "" : raw.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(markdown|md)?\\s*", "").replaceAll("\\s*```$", "").strip();
        }
        return cleaned;
    }
}
