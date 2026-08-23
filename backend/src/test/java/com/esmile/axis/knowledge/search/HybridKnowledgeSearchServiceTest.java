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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** {@link HybridKnowledgeSearchService}：RRF 融合排序、snippet 优先级、降级路径、rerank 应用。 */
@ExtendWith(MockitoExtension.class)
class HybridKnowledgeSearchServiceTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private KnowledgeItemRepository itemRepository;

    private static final KnowledgeReranker NOOP_RERANKER = (query, candidates) -> candidates;

    private HybridKnowledgeSearchService service(KnowledgeReranker reranker) {
        KeywordKnowledgeSearchService keywordLane = new KeywordKnowledgeSearchService(itemRepository);
        VectorKnowledgeSearchService vectorLane = new VectorKnowledgeSearchService(
                () -> vectorStore, keywordLane, 10, 0.0);
        return new HybridKnowledgeSearchService(vectorLane, keywordLane, reranker, 10);
    }

    private void mockVectorHits(String... itemIds) {
        List<Document> docs = new ArrayList<>();
        for (String id : itemIds) {
            docs.add(new Document("chunk-" + id,
                    Map.of(KnowledgeIndexService.META_ITEM_ID, id, KnowledgeIndexService.META_TITLE, "t-" + id)));
        }
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);
    }

    private void mockKeywordHits(String... itemIds) {
        List<KnowledgeItem> items = new ArrayList<>();
        for (String id : itemIds) {
            KnowledgeItem item = KnowledgeItem.builder()
                    .type(KnowledgeType.NOTE).title("t-" + id).content("content-" + id).build();
            item.setId(id);
            items.add(item);
        }
        when(itemRepository.search(any(), any(), any(), any())).thenReturn(items);
    }

    @Test
    void search_itemInBothLanes_ranksAboveSingleLaneHits() {
        mockVectorHits("v1", "both", "v2");
        mockKeywordHits("k1", "both");

        List<KnowledgeSearchHit> hits = service(NOOP_RERANKER).search("q");

        // both = 1/(60+2) + 1/(60+2) > v1 = 1/(60+1) > k1 = 1/(60+1) 之后的单路项
        assertThat(hits).extracting(KnowledgeSearchHit::itemId)
                .containsExactly("both", "v1", "k1", "v2");
    }

    @Test
    void search_dualLaneHit_prefersVectorSnippet() {
        mockVectorHits("i1");
        mockKeywordHits("i1");

        List<KnowledgeSearchHit> hits = service(NOOP_RERANKER).search("q");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).snippet()).isEqualTo("chunk-i1");
    }

    @Test
    void search_vectorLaneThrows_degradesToKeywordTop5() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("embedding api down"));
        mockKeywordHits("k1", "k2", "k3", "k4", "k5", "k6");

        List<KnowledgeSearchHit> hits = service(NOOP_RERANKER).search("q");

        assertThat(hits).extracting(KnowledgeSearchHit::itemId)
                .containsExactly("k1", "k2", "k3", "k4", "k5");
    }

    @Test
    void search_storeNull_vectorLaneEmpty_fusedEqualsKeywordOrder() {
        KeywordKnowledgeSearchService keywordLane = new KeywordKnowledgeSearchService(itemRepository);
        VectorKnowledgeSearchService vectorLane = new VectorKnowledgeSearchService(() -> null, keywordLane, 10, 0.0);
        HybridKnowledgeSearchService degraded = new HybridKnowledgeSearchService(
                vectorLane, keywordLane, NOOP_RERANKER, 10);
        mockKeywordHits("k1", "k2");

        List<KnowledgeSearchHit> hits = degraded.search("q");

        assertThat(hits).extracting(KnowledgeSearchHit::itemId).containsExactly("k1", "k2");
    }

    @Test
    void search_rerankerApplied_thenLimitedToFive() {
        mockVectorHits("v1", "v2", "v3", "v4", "v5", "v6");
        mockKeywordHits();
        KnowledgeReranker reversing = (query, candidates) -> candidates.reversed();

        List<KnowledgeSearchHit> hits = service(reversing).search("q");

        assertThat(hits).extracting(KnowledgeSearchHit::itemId)
                .containsExactly("v6", "v5", "v4", "v3", "v2");
    }

    @Test
    void rrfMerge_respectsLimit() {
        List<KnowledgeSearchHit> vector = List.of(
                new KnowledgeSearchHit("v1", "t", "s"), new KnowledgeSearchHit("v2", "t", "s"));
        List<KnowledgeSearchHit> keyword = List.of(new KnowledgeSearchHit("k1", "t", "s"));

        assertThat(HybridKnowledgeSearchService.rrfMerge(vector, keyword, 2)).hasSize(2);
    }
}
