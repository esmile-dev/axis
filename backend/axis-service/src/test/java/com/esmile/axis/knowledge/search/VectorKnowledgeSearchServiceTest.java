package com.esmile.axis.knowledge.search;

import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link VectorKnowledgeSearchService}：向量命中映射、按条目去重、运行时异常降级关键词。 */
@ExtendWith(MockitoExtension.class)
class VectorKnowledgeSearchServiceTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private KnowledgeItemRepository itemRepository;

    private VectorKnowledgeSearchService service() {
        return new VectorKnowledgeSearchService(() -> vectorStore, new KeywordKnowledgeSearchService(itemRepository));
    }

    @Test
    void search_mapsDocumentsToHits() {
        Document doc = new Document("c".repeat(300),
                Map.of(KnowledgeIndexService.META_ITEM_ID, "i1", KnowledgeIndexService.META_TITLE, "t1"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<KnowledgeSearchHit> hits = service().search("q");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).itemId()).isEqualTo("i1");
        assertThat(hits.get(0).title()).isEqualTo("t1");
        assertThat(hits.get(0).snippet()).hasSize(200);
    }

    @Test
    void search_multipleChunksSameItem_deduplicated() {
        List<Document> docs = List.of(
                new Document("chunk-a", Map.of(KnowledgeIndexService.META_ITEM_ID, "i1", KnowledgeIndexService.META_TITLE, "t1")),
                new Document("chunk-b", Map.of(KnowledgeIndexService.META_ITEM_ID, "i1", KnowledgeIndexService.META_TITLE, "t1")),
                new Document("chunk-c", Map.of(KnowledgeIndexService.META_ITEM_ID, "i2", KnowledgeIndexService.META_TITLE, "t2")));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);

        List<KnowledgeSearchHit> hits = service().search("q");

        assertThat(hits).extracting(KnowledgeSearchHit::itemId).containsExactly("i1", "i2");
        assertThat(hits.get(0).snippet()).isEqualTo("chunk-a"); // 保留相似度最高的第一块
    }

    @Test
    void search_topKFiveRequested() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service().search("q");

        var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getTopK()).isEqualTo(5);
        assertThat(captor.getValue().getQuery()).isEqualTo("q");
    }

    @Test
    void search_embeddingThrows_fallsBackToKeywordWithoutThrowing() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("embedding api down"));
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.NOTE).title("降级命中").content("content").build();
        item.setId("i9");
        when(itemRepository.search(null, null, null, "q")).thenReturn(List.of(item));

        List<KnowledgeSearchHit> hits = service().search("q");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).itemId()).isEqualTo("i9");
    }

    @Test
    void search_fallbackAlsoEmpty_returnsEmptyNotThrow() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("embedding api down"));
        when(itemRepository.search(null, null, null, "q")).thenReturn(List.of());

        assertThatCode(() -> assertThat(service().search("q")).isEmpty()).doesNotThrowAnyException();
    }

    @Test
    void search_storeNull_degradesToKeyword() {
        // store 降级中（pgvector 缺失/重建失败）→ 直接走关键词检索
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.NOTE).title("降级命中").content("content").build();
        item.setId("i9");
        when(itemRepository.search(null, null, null, "q")).thenReturn(List.of(item));
        VectorKnowledgeSearchService degraded = new VectorKnowledgeSearchService(() -> null,
                new KeywordKnowledgeSearchService(itemRepository));

        List<KnowledgeSearchHit> hits = degraded.search("q");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).itemId()).isEqualTo("i9");
    }
}
