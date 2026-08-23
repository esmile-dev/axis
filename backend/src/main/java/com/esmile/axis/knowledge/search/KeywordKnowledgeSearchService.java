package com.esmile.axis.knowledge.search;

import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词泳道：复用列表检索的 JPQL LIKE 匹配；snippet 为 content 前 200 字符。
 *
 * <p>query 先分词再逐 token 匹配（RetrievalEval 发现整串 LIKE 对自然语言查询几乎零贡献）：
 * 英文/数字整词保留，中文连续段切二元组（bigram）；按命中的不同 token 数打分排序。
 * 单 token（列表筛选等场景）保持原有整串 LIKE 行为。
 */
@RequiredArgsConstructor
public class KeywordKnowledgeSearchService implements KnowledgeSearchService {

    static final int TOP_K = 5;
    static final int SNIPPET_MAX_CHARS = 200;

    /** 英文/数字词（允许内部 ._:@ 等符号，保住 ef_construction、1:15 这类 token）与中文连续段。 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.:@/-]*|[\\u4e00-\\u9fff]+");

    private final KnowledgeItemRepository itemRepository;

    @Override
    public List<KnowledgeSearchHit> search(String query) {
        return search(query, TOP_K);
    }

    /** 关键词泳道（供混合检索组合）：分词匹配，按命中 token 数排序，取前 {@code limit} 条。 */
    List<KnowledgeSearchHit> search(String query, int limit) {
        List<String> tokens = tokenize(query);
        if (tokens.size() <= 1) {
            return itemRepository.search(null, null, null, query).stream()
                    .limit(limit)
                    .map(item -> new KnowledgeSearchHit(item.getId(), item.getTitle(), snippet(item.getContent())))
                    .toList();
        }
        Map<String, KnowledgeItem> byId = new LinkedHashMap<>();
        Map<String, Integer> scores = new HashMap<>();
        for (String token : tokens) {
            for (KnowledgeItem item : itemRepository.searchByToken(token)) {
                byId.putIfAbsent(item.getId(), item);
                scores.merge(item.getId(), 1, Integer::sum);
            }
        }
        return byId.values().stream()
                .sorted(Comparator.comparingInt((KnowledgeItem item) -> scores.get(item.getId())).reversed())
                .limit(limit)
                .map(item -> new KnowledgeSearchHit(item.getId(), item.getTitle(), snippet(item.getContent())))
                .toList();
    }

    /**
     * 分词：英文/数字整词保留（小写化）；中文连续段长度 ≥3 切 bigram，长度 2 整词保留，
     * 单字丢弃（"的""是"这类单字 LIKE 命中噪声太大）。
     */
    static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher m = TOKEN_PATTERN.matcher(query);
        while (m.find()) {
            String group = m.group();
            if (group.codePointAt(0) < 0x4e00) {
                tokens.add(group.toLowerCase());
            } else if (group.length() == 2) {
                tokens.add(group);
            } else if (group.length() > 2) {
                for (int i = 0; i + 2 <= group.length(); i++) {
                    tokens.add(group.substring(i, i + 2));
                }
            }
        }
        return new ArrayList<>(tokens);
    }

    static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SNIPPET_MAX_CHARS ? content : content.substring(0, SNIPPET_MAX_CHARS);
    }
}
