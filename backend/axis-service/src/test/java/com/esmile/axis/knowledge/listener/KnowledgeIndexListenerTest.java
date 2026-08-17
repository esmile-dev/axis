package com.esmile.axis.knowledge.listener;

import com.esmile.axis.knowledge.search.KnowledgeIndexService;
import com.esmile.axis.knowledge.search.KnowledgeItemUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/** 标题变更事件 → 幂等向量重建的委托。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIndexListenerTest {

    @Mock
    private KnowledgeIndexService knowledgeIndexService;

    @Test
    void onItemUpdated_reindexesItem() {
        KnowledgeIndexListener listener = new KnowledgeIndexListener(knowledgeIndexService);

        listener.onItemUpdated(new KnowledgeItemUpdatedEvent("i1"));

        verify(knowledgeIndexService).indexItem("i1");
    }
}
