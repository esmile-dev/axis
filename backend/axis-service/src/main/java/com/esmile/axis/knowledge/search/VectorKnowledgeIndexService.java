package com.esmile.axis.knowledge.search;

import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** pgvector 索引实现：分块 + embedding 写入 vector_store；失败仅记录 WARN，不向调用方传播。 */
@Slf4j
@RequiredArgsConstructor
public class VectorKnowledgeIndexService implements KnowledgeIndexService {

    private final VectorStore vectorStore;
    private final KnowledgeItemRepository itemRepository;

    @Override
    public void indexItem(String itemId) {
        Optional<KnowledgeItem> found = itemRepository.findById(itemId);
        if (found.isEmpty()) {
            log.debug("knowledge.index.skip item={} reason=item-missing", itemId);
            return;
        }
        KnowledgeItem item = found.get();
        try {
            deleteVectors(itemId);
            List<Document> docs = KnowledgeChunker.chunk(item.getContent()).stream()
                    .map(chunk -> new Document(chunk,
                            Map.of(META_ITEM_ID, itemId, META_TITLE, item.getTitle())))
                    .toList();
            if (docs.isEmpty()) {
                log.debug("knowledge.index.skip item={} reason=empty-content", itemId);
                return;
            }
            vectorStore.add(docs);
            log.info("knowledge.index.done item={} chunks={}", itemId, docs.size());
        } catch (Exception e) {
            log.warn("knowledge.index.failed item={} reason={}", itemId, e.toString());
        }
    }

    @Override
    public void removeItem(String itemId) {
        try {
            deleteVectors(itemId);
            log.info("knowledge.index.removed item={}", itemId);
        } catch (Exception e) {
            log.warn("knowledge.index.remove-failed item={} reason={}", itemId, e.toString());
        }
    }

    private void deleteVectors(String itemId) {
        vectorStore.delete(new FilterExpressionBuilder().eq(META_ITEM_ID, itemId).build());
    }
}
