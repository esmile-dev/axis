package com.esmile.axis.knowledge.search;

import lombok.extern.slf4j.Slf4j;

/** pgvector 不可用时的空实现：索引步骤静默跳过（DEBUG 日志）。 */
@Slf4j
public class NoopKnowledgeIndexService implements KnowledgeIndexService {

    @Override
    public void indexItem(String itemId) {
        log.debug("knowledge.index.skip item={} reason=pgvector-unavailable", itemId);
    }

    @Override
    public void removeItem(String itemId) {
        log.debug("knowledge.index.remove-skip item={} reason=pgvector-unavailable", itemId);
    }
}
