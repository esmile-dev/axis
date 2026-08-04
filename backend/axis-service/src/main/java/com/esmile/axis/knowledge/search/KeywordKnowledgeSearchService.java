package com.esmile.axis.knowledge.search;

import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;

import java.util.List;

/** 关键词降级实现：复用列表检索的 JPQL LIKE 匹配，取前 5 条，snippet 为 content 前 200 字符。 */
@RequiredArgsConstructor
public class KeywordKnowledgeSearchService implements KnowledgeSearchService {

    static final int TOP_K = 5;
    static final int SNIPPET_MAX_CHARS = 200;

    private final KnowledgeItemRepository itemRepository;

    @Override
    public List<KnowledgeSearchHit> search(String query) {
        return itemRepository.search(null, null, null, query).stream()
                .limit(TOP_K)
                .map(item -> new KnowledgeSearchHit(item.getId(), item.getTitle(), snippet(item.getContent())))
                .toList();
    }

    static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SNIPPET_MAX_CHARS ? content : content.substring(0, SNIPPET_MAX_CHARS);
    }
}
