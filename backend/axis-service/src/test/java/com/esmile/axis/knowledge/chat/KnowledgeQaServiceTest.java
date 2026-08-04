package com.esmile.axis.knowledge.chat;

import com.esmile.axis.config.AiConfigService;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KnowledgeQaService}: 404 on missing item, conversationId rule,
 * system prompt wiring, and the no-tools contract (mock-chain verification,
 * following {@code KnowledgeArtifactGeneratorTest}'s ChatClient mocking style).
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeQaServiceTest {

    @Mock
    private KnowledgeItemRepository itemRepository;
    @Mock
    private KnowledgeArtifactRepository artifactRepository;
    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private ChatMemory chatMemory;

    private KnowledgeQaService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeQaService(itemRepository, artifactRepository, aiConfigService, chatMemory);
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

    private ChatClient.ChatClientRequestSpec mockLlm(String... tokens) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(Advisor.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(Flux.just(tokens));
        return spec;
    }

    @Test
    void chat_streamsTokensAndUsesPerItemConversationIdWithoutTools() {
        KnowledgeItem item = item();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemIdAndKind("i1", ArtifactKind.SUMMARY))
                .thenReturn(Optional.of(KnowledgeArtifact.builder().item(item).kind(ArtifactKind.SUMMARY)
                        .content("## TL;DR\n总结").build()));
        ChatClient.ChatClientRequestSpec spec = mockLlm("先", "更新数据库");

        assertThat(service.chat("i1", "怎么做缓存更新？").collectList().block())
                .containsExactly("先", "更新数据库");

        // conversationId 规则：knowledge-{itemId}
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisorConsumer =
                ArgumentCaptor.forClass(Consumer.class);
        verify(spec).advisors(advisorConsumer.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        advisorConsumer.getValue().accept(advisorSpec);
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, "knowledge-i1");

        // 不挂任何 Tool（只读问答）
        verify(spec, never()).tools(any(Object[].class));

        // system prompt 注入标题/总结/原文
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(spec).system(systemPrompt.capture());
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
        ChatClient.ChatClientRequestSpec spec = mockLlm("答");

        assertThat(service.chat("i1", "问题").collectList().block()).containsExactly("答");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(spec).system(systemPrompt.capture());
        assertThat(systemPrompt.getValue()).doesNotContain("条目总结");
    }

    @Test
    void chat_itemMissing_throws404SynchronouslyWithoutLlm() {
        when(itemRepository.findById("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.chat("gone", "问题"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verifyNoInteractions(aiConfigService, artifactRepository, chatMemory);
    }

    @Test
    void conversationId_followsKnowledgePrefixRule() {
        assertThat(KnowledgeQaService.conversationId("abc")).isEqualTo("knowledge-abc");
    }
}
