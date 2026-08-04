package com.esmile.axis.knowledge.chat;

/**
 * Prompt contract for knowledge item QA (design.md §6). Constants are public so the
 * T-011 evaluation harness can reuse them.
 */
public final class KnowledgeQaPrompts {

    /** Inputs longer than this are truncated before being sent to the LLM (same rule as artifact prompts). */
    public static final int MAX_CONTENT_CHARS = 100_000;

    public static final String PROMPT_QA = """
            你是知识库条目的问答助手。仅依据下方提供的条目内容回答用户问题。

            规则：
            - 只依据所给的标题、总结与原文回答；禁止动用外部知识，禁止编造原文没有的事实、数据或结论。
            - 问题超出条目内容范围时，明确回答「原文未提及」并简要说清原文讲了什么，不要强行作答。
            - 语言跟随用户问题（中文优先）。
            - 回答简洁，可用 Markdown 排版。

            条目标题：{title}
            {summary}
            {truncation}
            条目原文：
            {content}
            """;

    private static final String SUMMARY_SECTION = "条目总结：\n{summary}\n";

    private static final String TRUNCATION_NOTE =
            "（注：原文内容过长已截断，以下仅前 " + MAX_CONTENT_CHARS + " 字符）";

    private KnowledgeQaPrompts() {
    }

    /**
     * Build the QA system prompt: title + summary (omitted when absent) + content
     * (truncated at {@link #MAX_CONTENT_CHARS} with an explicit note).
     */
    public static String qaSystemPrompt(String title, String summary, String content) {
        String body = content == null ? "" : content;
        String note = "";
        if (body.length() > MAX_CONTENT_CHARS) {
            body = body.substring(0, MAX_CONTENT_CHARS);
            note = TRUNCATION_NOTE;
        }
        String summarySection = summary == null || summary.isBlank()
                ? ""
                : SUMMARY_SECTION.replace("{summary}", summary);
        return PROMPT_QA
                .replace("{title}", title == null ? "" : title)
                .replace("{summary}", summarySection)
                .replace("{truncation}", note)
                .replace("{content}", body);
    }
}
