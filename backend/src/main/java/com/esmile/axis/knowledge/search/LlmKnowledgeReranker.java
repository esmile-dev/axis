package com.esmile.axis.knowledge.search;

import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.llm.ChatGateway.LlmOptions;
import com.esmile.axis.llm.LlmFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM reranker：把 RRF 融合后的候选连同 query 交给 LLM 排序，经结构化输出取回重排序号。
 * 零新依赖（复用 {@link ChatGateway}），候选仅 top-10，单次调用成本可控。
 * 任何失败（超时/解析错误/序号非法）都返回原顺序——rerank 只是增强。
 */
@Slf4j
@RequiredArgsConstructor
public class LlmKnowledgeReranker implements KnowledgeReranker {

    private static final String PROMPT = """
            你是检索结果排序器。给定用户查询和候选文档列表（编号从 1 开始），按与查询的相关度从高到低重新排序。

            查询：{query}

            候选文档：
            {candidates}

            输出字段要求：
            - order: 按相关度从高到低重排后的文档编号数组
            """;

    private final ChatGateway chatGateway;

    /** LLM 重排输出，经 Spring AI 结构化输出绑定。 */
    public record RerankJson(List<Integer> order) {
    }

    @Override
    public List<KnowledgeSearchHit> rerank(String query, List<KnowledgeSearchHit> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        try {
            RerankJson out = chatGateway.callEntity(buildPrompt(query, candidates), RerankJson.class,
                    new LlmOptions(Duration.ofSeconds(30), null, LlmFeature.RERANK));
            if (out.order() == null) {
                throw new IllegalStateException("rerank output missing order");
            }
            return applyOrder(candidates, out.order());
        } catch (Exception e) {
            log.warn("knowledge.rerank.fallback reason={} — rerank 失败，保持融合顺序", e.toString());
            return candidates;
        }
    }

    private String buildPrompt(String query, List<KnowledgeSearchHit> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            KnowledgeSearchHit h = candidates.get(i);
            sb.append(i + 1).append(". 【").append(h.title()).append("】").append(h.snippet()).append('\n');
        }
        return PROMPT.replace("{query}", query).replace("{candidates}", sb.toString().strip());
    }

    /** 应用 LLM 排序：越界/重复序号忽略，漏掉的候选按原顺序补尾，保证候选集合不变。 */
    static List<KnowledgeSearchHit> applyOrder(List<KnowledgeSearchHit> candidates, List<Integer> order) {
        List<KnowledgeSearchHit> reranked = new ArrayList<>(candidates.size());
        boolean[] used = new boolean[candidates.size()];
        for (int idx : order) {
            int i = idx - 1;
            if (i >= 0 && i < candidates.size() && !used[i]) {
                used[i] = true;
                reranked.add(candidates.get(i));
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (!used[i]) {
                reranked.add(candidates.get(i));
            }
        }
        return reranked;
    }
}
