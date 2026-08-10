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
import java.util.function.Supplier;

/**
 * pgvector 索引实现：分块 + embedding 写入 vector_store；失败仅记录 WARN，不向调用方传播。
 * store 经 Supplier 每次调用动态获取（AI 配置重载后 store 重建即时生效）；为 null（降级中）时跳过。
 */
@Slf4j
@RequiredArgsConstructor
public class VectorKnowledgeIndexService implements KnowledgeIndexService {

    private final Supplier<VectorStore> vectorStoreSupplier;
    private final KnowledgeItemRepository itemRepository;

    @Override
    public void indexItem(String itemId) {
        VectorStore vectorStore = vectorStoreSupplier.get();
        if (vectorStore == null) {
            log.debug("knowledge.index.skip item={} reason=vector-disabled", itemId);
            return;
        }
        Optional<KnowledgeItem> found = itemRepository.findById(itemId);
        if (found.isEmpty()) {
            log.debug("knowledge.index.skip item={} reason=item-missing", itemId);
            return;
        }
        KnowledgeItem item = found.get();
        try {
            deleteVectors(vectorStore, itemId);
            List<Document> docs = KnowledgeChunker.chunk(item.getTitle(), item.getContent()).stream()
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
        VectorStore vectorStore = vectorStoreSupplier.get();
        if (vectorStore == null) {
            return;
        }
        try {
            deleteVectors(vectorStore, itemId);
            log.info("knowledge.index.removed item={}", itemId);
        } catch (Exception e) {
            log.warn("knowledge.index.remove-failed item={} reason={}", itemId, e.toString());
        }
    }

    private void deleteVectors(VectorStore vectorStore, String itemId) {
        vectorStore.delete(new FilterExpressionBuilder().eq(META_ITEM_ID, itemId).build());
    }
}
