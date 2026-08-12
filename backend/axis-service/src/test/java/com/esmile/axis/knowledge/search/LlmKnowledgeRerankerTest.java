package com.esmile.axis.knowledge.search;

import com.esmile.axis.config.AiConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** {@link LlmKnowledgeReranker}：正常重排、容错解析（围栏/噪声/非法序号）、失败保持原序。 */
@ExtendWith(MockitoExtension.class)
class LlmKnowledgeRerankerTest {

    @Mock
    private AiConfigService aiConfigService;

    private ChatClient.CallResponseSpec mockLlm(String response) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec resp = mock(ChatClient.CallResponseSpec.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(resp);
        when(resp.content()).thenReturn(response);
        return resp;
    }

    private static List<KnowledgeSearchHit> candidates(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new KnowledgeSearchHit(id, "t-" + id, "s-" + id)).toList();
    }

    @Test
    void rerank_llmReordersCandidates() {
        mockLlm("[3,1,2]");
        LlmKnowledgeReranker reranker = new LlmKnowledgeReranker(aiConfigService);

        List<KnowledgeSearchHit> result = reranker.rerank("q", candidates("a", "b", "c"));

        assertThat(result).extracting(KnowledgeSearchHit::itemId).containsExactly("c", "a", "b");
    }

    @Test
    void rerank_outputWithFenceAndNoise_stillParsed() {
        mockLlm("排序结果：\n```json\n[2,1]\n```");
        LlmKnowledgeReranker reranker = new LlmKnowledgeReranker(aiConfigService);

        List<KnowledgeSearchHit> result = reranker.rerank("q", candidates("a", "b"));

        assertThat(result).extracting(KnowledgeSearchHit::itemId).containsExactly("b", "a");
    }

    @Test
    void rerank_outOfRangeAndDuplicateIndices_ignoredAndBackfilled() {
        List<KnowledgeSearchHit> input = candidates("a", "b", "c");

        List<KnowledgeSearchHit> result = LlmKnowledgeReranker.applyOrder(input, List.of(2, 99, 2));

        assertThat(result).extracting(KnowledgeSearchHit::itemId).containsExactly("b", "a", "c");
    }

    @Test
    void rerank_unparseableOutput_keepsOriginalOrder() {
        mockLlm("这不是 JSON");
        List<KnowledgeSearchHit> input = candidates("a", "b", "c");
        LlmKnowledgeReranker reranker = new LlmKnowledgeReranker(aiConfigService);

        assertThat(reranker.rerank("q", input)).isEqualTo(input);
    }

    @Test
    void rerank_llmThrows_keepsOriginalOrder() {
        ChatClient chatClient = mock(ChatClient.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenThrow(new RuntimeException("boom"));
        List<KnowledgeSearchHit> input = candidates("a", "b");
        LlmKnowledgeReranker reranker = new LlmKnowledgeReranker(aiConfigService);

        assertThat(reranker.rerank("q", input)).isEqualTo(input);
    }

    @Test
    void rerank_singleCandidate_skipsLlmCall() {
        LlmKnowledgeReranker reranker = new LlmKnowledgeReranker(aiConfigService);
        List<KnowledgeSearchHit> input = candidates("a");

        assertThat(reranker.rerank("q", input)).isEqualTo(input);
        verify(aiConfigService, never()).get();
    }
}
