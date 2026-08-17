package com.esmile.axis.knowledge.listener;

import com.esmile.axis.knowledge.search.KnowledgeIndexService;
import com.esmile.axis.knowledge.search.KnowledgeItemUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 标题变更后的向量重建：AFTER_COMMIT 保证只重建已提交的数据；
 * 异步执行（与产物生成管线同池）避免 embedding 调用拖慢 PATCH 响应。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeIndexListener {

    private final KnowledgeIndexService knowledgeIndexService;

    @Async("knowledgeTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onItemUpdated(KnowledgeItemUpdatedEvent event) {
        knowledgeIndexService.indexItem(event.itemId());
    }
}
