package com.esmile.axis.knowledge.search;

import com.esmile.axis.config.ChatGateway;
import com.esmile.axis.config.ChatGateway.LlmOptions;
import com.esmile.axis.knowledge.search.LlmKnowledgeReranker.RerankJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** {@link LlmKnowledgeReranker}：正常重排、null order 容错、失败保持原序、30s 超时经 LlmOptions 传递。 */
@ExtendWith(MockitoExtension.class)
class LlmKnowledgeRerankerTest {

    @Mock
    private ChatGateway chatGateway;

    private static List<KnowledgeSearchHit> candidates(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new KnowledgeSearchHit(id, "t-" + id, "s-" + id)).toList();
    }

    @Test
    void rerank_llmReordersCandidates_with30sTimeout() {
        when(chatGateway.callEntity(any(String.class), eq(RerankJson.class), any()))
                .thenReturn(new RerankJson(List.of(3, 1, 2)));
        LlmKnowledgeReranker reranker = new LlmKnowledgeReranker(chatGateway);

        List<KnowledgeSearchHit> result = reranker.rerank("q", candidates("a", "b", "c"));

        assertThat(result).extracting(KnowledgeSearchHit::itemId).containsExactly("c", "a", "b");
        ArgumentCaptor<LlmOptions> optionsCaptor = ArgumentCaptor.forClass(LlmOptions.class);
        verify(chatGateway).callEntity(any(String.class), eq(RerankJson.class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(optionsCaptor.getValue().memoryConversationId()).isNull();
    }

    @Test
    void rerank_outOfRangeAndDuplicateIndices_ignoredAndBackfilled() {
        List<KnowledgeSearchHit> input = candidates("a", "b", "c");

        List<KnowledgeSearchHit> result = LlmKnowledgeReranker.applyOrder(input, List.of(2, 99, 2));

        assertThat(result).extracting(KnowledgeSearchHit::itemId).containsExactly("b", "a", "c");
    }

    @Test
    void rerank_nullOrder_keepsOriginalOrder() {
        when(chatGateway.callEntity(any(String.class), eq(RerankJson.class), any())).thenReturn(new RerankJson(null));
        List<KnowledgeSearchHit> input = candidates("a", "b", "c");
        LlmKnowledgeReranker reranker = new LlmKnowledgeReranker(chatGateway);

        assertThat(reranker.rerank("q", input)).isEqualTo(input);
    }

    @Test
    void rerank_llmThrows_keepsOriginalOrder() {
        when(chatGateway.callEntity(any(String.class), eq(RerankJson.class), any()))
                .thenThrow(new RuntimeException("boom"));
        List<KnowledgeSearchHit> input = candidates("a", "b");
        LlmKnowledgeReranker reranker = new LlmKnowledgeReranker(chatGateway);

        assertThat(reranker.rerank("q", input)).isEqualTo(input);
    }

    @Test
    void rerank_singleCandidate_skipsLlmCall() {
        LlmKnowledgeReranker reranker = new LlmKnowledgeReranker(chatGateway);
        List<KnowledgeSearchHit> input = candidates("a");

        assertThat(reranker.rerank("q", input)).isEqualTo(input);
        verifyNoInteractions(chatGateway);
    }
}
