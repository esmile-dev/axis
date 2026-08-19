package com.esmile.axis.llm;

/**
 * LLM 调用的业务来源维度 —— 回答"成本花在哪个功能上"。
 * 每个调用点在 {@code ChatGateway.LlmOptions} / {@code AgentService} 中显式标注。
 */
public enum LlmFeature {
    /** /chat 页 Agent 对话（含 chatSync；一轮用户消息记一次，tool loop 内部往返不逐次计） */
    AGENT_CHAT,
    /** Issue PRD 扩写（/api/agent/expand） */
    PRD_EXPAND,
    /** 知识库 RAG 引用问答 */
    KNOWLEDGE_QA,
    /** 知识库自由问答 */
    KNOWLEDGE_ASK,
    /** 混合检索 LLM rerank */
    RERANK,
    /** Daily Digest 条目总结 */
    DIGEST_SUMMARY,
    /** 会话标题生成 */
    TITLE_GEN,
    /** 知识条目摘要 artifact 生成 */
    ARTIFACT_GEN
}
