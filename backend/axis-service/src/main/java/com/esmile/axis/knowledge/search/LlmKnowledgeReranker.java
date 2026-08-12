package com.esmile.axis.knowledge.search;

import com.esmile.axis.config.AiConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM reranker：把 RRF 融合后的候选连同 query 交给 ChatClient 排序，输出序号 JSON 数组。
 * 零新依赖（复用现有 ChatClient），候选仅 top-10，单次调用成本可控。
 * 任何失败（超时/解析错误/序号非法）都返回原顺序——rerank 只是增强。
 */
@Slf4j
@RequiredArgsConstructor
public class LlmKnowledgeReranker implements KnowledgeReranker {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String PROMPT = """
            你是检索结果排序器。给定用户查询和候选文档列表（编号从 1 开始），按与查询的相关度从高到低重新排序。

            查询：{query}

            候选文档：
            {candidates}

            只输出一个 JSON 数组，元素为重排后的文档编号，例如 [3,1,2]。不要输出任何其他内容。
            """;

    private final AiConfigService aiConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<KnowledgeSearchHit> rerank(String query, List<KnowledgeSearchHit> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        try {
            String raw = aiConfigService.get().prompt()
                    .user(buildPrompt(query, candidates))
                    .options(OpenAiChatOptions.builder().timeout(TIMEOUT))
                    .call()
                    .content();
            return applyOrder(candidates, parseOrder(raw));
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

    /** 解析 LLM 输出的序号数组；容忍 markdown 围栏与前后噪声。 */
    List<Integer> parseOrder(String raw) throws Exception {
        if (raw == null) {
            throw new IllegalArgumentException("empty rerank output");
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no json array in rerank output");
        }
        return objectMapper.readValue(raw.substring(start, end + 1), new TypeReference<>() {
        });
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
