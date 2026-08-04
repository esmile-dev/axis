package com.esmile.axis.knowledge.search;

import java.util.ArrayList;
import java.util.List;

/** 纯函数分块器：~1000 字符一块，块间重叠 100 字符（design.md §6）。 */
public final class KnowledgeChunker {

    static final int CHUNK_SIZE = 1000;
    static final int OVERLAP = 100;

    private KnowledgeChunker() {
    }

    /** 空/空白内容返回空列表；末尾不足一整块时收尾为最后一块；尾巴 ≤ 重叠区时已含于上一块，不再重复切块。 */
    public static List<String> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int step = CHUNK_SIZE - OVERLAP;
        for (int start = 0; start < content.length(); start += step) {
            if (start > 0 && content.length() - start <= OVERLAP) {
                break;
            }
            chunks.add(content.substring(start, Math.min(start + CHUNK_SIZE, content.length())));
        }
        return chunks;
    }
}
