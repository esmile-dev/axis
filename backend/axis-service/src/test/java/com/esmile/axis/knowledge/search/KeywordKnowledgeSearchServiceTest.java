package com.esmile.axis.knowledge.search;

import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link KeywordKnowledgeSearchService}：复用列表 JPQL 检索、top-5 截断、snippet 取 content 前 200 字符。 */
@ExtendWith(MockitoExtension.class)
class KeywordKnowledgeSearchServiceTest {

    @Mock
    private KnowledgeItemRepository itemRepository;

    @Test
    void search_delegatesToRepositoryKeywordQuery() {
        when(itemRepository.search(null, null, null, "q")).thenReturn(List.of());

        new KeywordKnowledgeSearchService(itemRepository).search("q");

        verify(itemRepository).search(null, null, null, "q");
    }

    @Test
    void search_mapsHitsAndTruncatesSnippet() {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.NOTE).title("t1").content("c".repeat(300)).build();
        item.setId("i1");
        when(itemRepository.search(null, null, null, "q")).thenReturn(List.of(item));

        List<KnowledgeSearchHit> hits = new KeywordKnowledgeSearchService(itemRepository).search("q");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).itemId()).isEqualTo("i1");
        assertThat(hits.get(0).title()).isEqualTo("t1");
        assertThat(hits.get(0).snippet()).hasSize(200);
    }

    @Test
    void search_moreThanFive_keepsTopFive() {
        List<KnowledgeItem> items = IntStream.range(0, 8)
                .mapToObj(i -> {
                    KnowledgeItem item = KnowledgeItem.builder()
                            .type(KnowledgeType.NOTE).title("t" + i).content("c" + i).build();
                    item.setId("i" + i);
                    return item;
                })
                .toList();
        when(itemRepository.search(null, null, null, "q")).thenReturn(items);

        List<KnowledgeSearchHit> hits = new KeywordKnowledgeSearchService(itemRepository).search("q");

        assertThat(hits).hasSize(5);
        assertThat(hits).extracting(KnowledgeSearchHit::itemId)
                .containsExactly("i0", "i1", "i2", "i3", "i4");
    }

    @Test
    void search_shortContent_snippetUntruncated() {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.NOTE).title("t").content("短内容").build();
        item.setId("i1");
        when(itemRepository.search(null, null, null, "q")).thenReturn(List.of(item));

        List<KnowledgeSearchHit> hits = new KeywordKnowledgeSearchService(itemRepository).search("q");

        assertThat(hits.get(0).snippet()).isEqualTo("短内容");
    }
}
