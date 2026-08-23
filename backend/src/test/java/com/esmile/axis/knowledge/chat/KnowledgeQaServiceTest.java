package com.esmile.axis.knowledge.chat;

import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.llm.ChatGateway.LlmOptions;
import com.esmile.axis.llm.LlmFeature;

import java.time.Duration;
import com.esmile.axis.knowledge.ArtifactKind;
import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeArtifact;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeArtifactRepository;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KnowledgeQaService}: 404 on missing item, conversationId rule
 * flowing into {@link LlmOptions#memoryConversationId()}, and system prompt wiring.
 * 记忆 advisor 的挂载由 {@link ChatGateway} 负责（见 {@code ChatGatewayTest}）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeQaServiceTest {

    @Mock
    private KnowledgeItemRepository itemRepository;
    @Mock
    private KnowledgeArtifactRepository artifactRepository;
    @Mock
    private ChatGateway chatGateway;

    private KnowledgeQaService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeQaService(itemRepository, artifactRepository, chatGateway);
    }

    private KnowledgeItem item() {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.ARTICLE)
                .title("缓存更新的套路")
                .content("正文：Cache Aside 先更新数据库再删缓存。")
                .build();
        item.setId("i1");
        return item;
    }

    private void mockLlm(String... tokens) {
        when(chatGateway.stream(any(), any(), any())).thenReturn(Flux.just(tokens));
    }

    @Test
    void chat_streamsTokensAndUsesPerItemConversationId() {
        KnowledgeItem item = item();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemIdAndKind("i1", ArtifactKind.SUMMARY))
                .thenReturn(Optional.of(KnowledgeArtifact.builder().item(item).kind(ArtifactKind.SUMMARY)
                        .content("## TL;DR\n总结").build()));
        mockLlm("先", "更新数据库");

        assertThat(service.chat("i1", "怎么做缓存更新？").collectList().block())
                .containsExactly("先", "更新数据库");

        // conversationId 规则：knowledge-{itemId}，经 LlmOptions 传给网关
        ArgumentCaptor<LlmOptions> optionsCaptor = ArgumentCaptor.forClass(LlmOptions.class);
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatGateway).stream(systemPrompt.capture(), any(String.class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().memoryConversationId()).isEqualTo("knowledge-i1");
        assertThat(optionsCaptor.getValue().timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(optionsCaptor.getValue().feature()).isEqualTo(LlmFeature.KNOWLEDGE_QA);

        // system prompt 注入标题/总结/原文
        assertThat(systemPrompt.getValue())
                .contains("缓存更新的套路")
                .contains("条目总结：\n## TL;DR\n总结")
                .contains("正文：Cache Aside 先更新数据库再删缓存。");
    }

    @Test
    void chat_noSummaryArtifact_promptOmitsSummarySection() {
        KnowledgeItem item = item();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemIdAndKind("i1", ArtifactKind.SUMMARY)).thenReturn(Optional.empty());
        mockLlm("答");

        assertThat(service.chat("i1", "问题").collectList().block()).containsExactly("答");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatGateway).stream(systemPrompt.capture(), any(String.class), any());
        assertThat(systemPrompt.getValue()).doesNotContain("条目总结");
    }

    @Test
    void chat_itemMissing_throws404SynchronouslyWithoutLlm() {
        when(itemRepository.findById("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.chat("gone", "问题"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verifyNoInteractions(chatGateway, artifactRepository);
    }

    @Test
    void conversationId_followsKnowledgePrefixRule() {
        assertThat(KnowledgeQaService.conversationId("abc")).isEqualTo("knowledge-abc");
    }
}
