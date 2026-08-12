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

    @Test
    void tokenize_latinAndDigitTokensKeptWhole_lowercased() {
        assertThat(KeywordKnowledgeSearchService.tokenize("MaxGCPauseMillis 参数"))
                .containsExactly("maxgcpausemillis", "参数");
        assertThat(KeywordKnowledgeSearchService.tokenize("1:15 是什么的参数"))
                .containsExactly("1:15", "是什", "什么", "么的", "的参", "参数");
    }

    @Test
    void tokenize_cjkRunSplitsIntoBigrams() {
        assertThat(KeywordKnowledgeSearchService.tokenize("依赖收集"))
                .containsExactly("依赖", "赖收", "收集");
        assertThat(KeywordKnowledgeSearchService.tokenize("过滤")).containsExactly("过滤");
        assertThat(KeywordKnowledgeSearchService.tokenize("的")).isEmpty(); // 单字噪声大，丢弃
    }

    @Test
    void search_multiTokenQuery_scoresByDistinctTokenHits() {
        KnowledgeItem both = KnowledgeItem.builder()
                .type(KnowledgeType.NOTE).title("t-both").content("alpha 和 beta 都命中").build();
        both.setId("i-both");
        KnowledgeItem oneOnly = KnowledgeItem.builder()
                .type(KnowledgeType.NOTE).title("t-one").content("只有 alpha").build();
        oneOnly.setId("i-one");
        when(itemRepository.searchByToken("alpha")).thenReturn(List.of(both, oneOnly));
        when(itemRepository.searchByToken("beta")).thenReturn(List.of(both));

        List<KnowledgeSearchHit> hits = new KeywordKnowledgeSearchService(itemRepository).search("alpha beta", 10);

        assertThat(hits).extracting(KnowledgeSearchHit::itemId).containsExactly("i-both", "i-one");
    }
}
