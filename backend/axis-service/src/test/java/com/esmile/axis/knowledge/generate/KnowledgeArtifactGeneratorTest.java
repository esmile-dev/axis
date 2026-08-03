package com.esmile.axis.knowledge.generate;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.knowledge.ArtifactKind;
import com.esmile.axis.knowledge.ArtifactStatus;
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

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KnowledgeArtifactGenerator}: state machine success/failure
 * paths, artifact upsert, per-artifact independence, and in-flight dedup.
 * ChatClient mocking follows {@code SummarizationServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeArtifactGeneratorTest {

    @Mock
    private KnowledgeItemRepository itemRepository;
    @Mock
    private KnowledgeArtifactRepository artifactRepository;
    @Mock
    private AiConfigService aiConfigService;

    private KnowledgeArtifactGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new KnowledgeArtifactGenerator(itemRepository, artifactRepository, aiConfigService);
    }

    private ChatClient.CallResponseSpec mockLlm() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec resp = mock(ChatClient.CallResponseSpec.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(resp);
        return resp;
    }

    private void stubModel() {
        when(aiConfigService.getConfig()).thenReturn(
                new AiConfigService.ResolvedConfig(null, "test", "key", "https://api.test", "gpt-test", "env"));
    }

    private KnowledgeItem item() {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.ARTICLE)
                .title("标题")
                .content("正文")
                .build();
        item.setId("i1");
        return item;
    }

    @Test
    void generateAll_success_bothArtifactsDoneAndPersisted() {
        KnowledgeItem item = item();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemIdAndKind(eq("i1"), any())).thenReturn(Optional.empty());
        ChatClient.CallResponseSpec resp = mockLlm();
        when(resp.content()).thenReturn(
                "```markdown\n## TL;DR\n总结\n```",
                "```\n# 中心主题\n## 分支\n```");
        stubModel();

        generator.generateAll("i1");

        assertThat(item.getSummaryStatus()).isEqualTo(ArtifactStatus.DONE);
        assertThat(item.getMindmapStatus()).isEqualTo(ArtifactStatus.DONE);
        ArgumentCaptor<KnowledgeArtifact> captor = ArgumentCaptor.forClass(KnowledgeArtifact.class);
        verify(artifactRepository, times(2)).save(captor.capture());
        KnowledgeArtifact summary = captor.getAllValues().get(0);
        assertThat(summary.getKind()).isEqualTo(ArtifactKind.SUMMARY);
        assertThat(summary.getContent()).isEqualTo("## TL;DR\n总结");
        assertThat(summary.getModel()).isEqualTo("gpt-test");
        assertThat(summary.getError()).isNull();
        KnowledgeArtifact mindmap = captor.getAllValues().get(1);
        assertThat(mindmap.getKind()).isEqualTo(ArtifactKind.MINDMAP);
        assertThat(mindmap.getContent()).isEqualTo("# 中心主题\n## 分支");
        assertThat(mindmap.getError()).isNull();
    }

    @Test
    void generateAll_summaryFails_mindmapStillDoneAndErrorRecorded() {
        KnowledgeItem item = item();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemIdAndKind(eq("i1"), any())).thenReturn(Optional.empty());
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec resp = mock(ChatClient.CallResponseSpec.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenThrow(new RuntimeException("boom")).thenReturn(resp);
        when(resp.content()).thenReturn("# 中心主题");
        stubModel();

        generator.generateAll("i1"); // must not throw

        assertThat(item.getSummaryStatus()).isEqualTo(ArtifactStatus.FAILED);
        assertThat(item.getMindmapStatus()).isEqualTo(ArtifactStatus.DONE);
        ArgumentCaptor<KnowledgeArtifact> captor = ArgumentCaptor.forClass(KnowledgeArtifact.class);
        verify(artifactRepository, times(2)).save(captor.capture());
        KnowledgeArtifact summary = captor.getAllValues().get(0);
        assertThat(summary.getKind()).isEqualTo(ArtifactKind.SUMMARY);
        assertThat(summary.getError()).contains("boom");
        assertThat(summary.getContent()).isEmpty();
        KnowledgeArtifact mindmap = captor.getAllValues().get(1);
        assertThat(mindmap.getKind()).isEqualTo(ArtifactKind.MINDMAP);
        assertThat(mindmap.getContent()).isEqualTo("# 中心主题");
        assertThat(mindmap.getError()).isNull();
    }

    @Test
    void generateOne_existingArtifact_updatesRowAndClearsError() {
        KnowledgeItem item = item();
        KnowledgeArtifact existing = KnowledgeArtifact.builder()
                .item(item).kind(ArtifactKind.SUMMARY).content("旧总结").model("old-model").error("old error")
                .build();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemIdAndKind("i1", ArtifactKind.SUMMARY))
                .thenReturn(Optional.of(existing));
        ChatClient.CallResponseSpec resp = mockLlm();
        when(resp.content()).thenReturn("## TL;DR\n新总结");
        stubModel();

        generator.generateOne("i1", ArtifactKind.SUMMARY);

        assertThat(item.getSummaryStatus()).isEqualTo(ArtifactStatus.DONE);
        verify(artifactRepository).save(existing);
        assertThat(existing.getContent()).isEqualTo("## TL;DR\n新总结");
        assertThat(existing.getModel()).isEqualTo("gpt-test");
        assertThat(existing.getError()).isNull();
    }

    @Test
    void generateOne_llmFails_statusFailedWithoutThrowing() {
        KnowledgeItem item = item();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemIdAndKind("i1", ArtifactKind.MINDMAP)).thenReturn(Optional.empty());
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenThrow(new RuntimeException("timeout"));

        generator.generateOne("i1", ArtifactKind.MINDMAP); // must not throw

        assertThat(item.getMindmapStatus()).isEqualTo(ArtifactStatus.FAILED);
        ArgumentCaptor<KnowledgeArtifact> captor = ArgumentCaptor.forClass(KnowledgeArtifact.class);
        verify(artifactRepository).save(captor.capture());
        assertThat(captor.getValue().getError()).contains("timeout");
    }

    @Test
    void generateAll_itemMissing_skipsSilently() {
        when(itemRepository.findById("gone")).thenReturn(Optional.empty());

        generator.generateAll("gone");

        verifyNoInteractions(aiConfigService, artifactRepository);
        verify(itemRepository, never()).save(any());
    }

    @Test
    void generateOne_alreadyInFlight_secondCallSkips() throws Exception {
        KnowledgeItem item = item();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemIdAndKind(eq("i1"), any())).thenReturn(Optional.empty());
        ChatClient.CallResponseSpec resp = mockLlm();
        stubModel();
        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(resp.content()).thenAnswer(inv -> {
            llmStarted.countDown();
            release.await();
            return "## TL;DR\n总结";
        });

        Thread worker = new Thread(() -> generator.generateOne("i1", ArtifactKind.SUMMARY));
        worker.start();
        assertThat(llmStarted.await(5, TimeUnit.SECONDS)).isTrue();

        generator.generateOne("i1", ArtifactKind.SUMMARY); // duplicate trigger while in-flight

        release.countDown();
        worker.join(5000);
        verify(resp, times(1)).content(); // LLM called once: the duplicate was skipped
    }
}
