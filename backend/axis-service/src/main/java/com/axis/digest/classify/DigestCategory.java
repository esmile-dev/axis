package com.axis.digest.classify;

/**
 * Four-tier article classification. T0 (AI_FRONTIER) wins if any AI keyword matches.
 *
 * <p>Priority order matches the FR:
 * <ol>
 *   <li>{@link #AI_FRONTIER} — LLM / GPT / multimodal / embodied / chips / frameworks / alignment</li>
 *   <li>{@link #TECH_INDUSTRY} — cloud, DB, language updates, OS internals, frontend eng, microservices</li>
 *   <li>{@link #FINANCE_TECH} — tech-company earnings, AI/hardware investment events</li>
 *   <li>{@link #OTHER} — fallback (regulations, everything else)</li>
 * </ol>
 */
public enum DigestCategory {
    AI_FRONTIER,
    TECH_INDUSTRY,
    FINANCE_TECH,
    OTHER
}