package com.esmile.axis.knowledge.generate;

import com.esmile.axis.llm.AiConfigService;
import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.llm.ChatGateway.LlmOptions;
import com.esmile.axis.llm.LlmFeature;
import com.esmile.axis.knowledge.ArtifactKind;
import com.esmile.axis.knowledge.ArtifactStatus;
import com.esmile.axis.knowledge.entity.KnowledgeArtifact;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeArtifactRepository;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import com.esmile.axis.knowledge.search.KnowledgeIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async artifact generation pipeline (design.md §5). Each artifact has its own
 * state machine on the item row: PENDING → GENERATING → DONE / FAILED.
 *
 * <p>Runs after the creating transaction has committed, so every status/artifact
 * write here is its own short transaction. LLM failures never propagate: the
 * artifact is marked FAILED with the error recorded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeArtifactGenerator {

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeArtifactRepository artifactRepository;
    private final AiConfigService aiConfigService;
    private final ChatGateway chatGateway;
    private final KnowledgeIndexService knowledgeIndexService;

    /** In-flight dedup: a repeat trigger for the same item+kind is accepted but skipped. */
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    /** Initial pipeline: SUMMARY first, then MINDMAP; one failure does not affect the other. */
    @Async("knowledgeTaskExecutor")
    public void generateAll(String itemId) {
        generate(itemId, ArtifactKind.SUMMARY);
        generate(itemId, ArtifactKind.MINDMAP);
        // FR-010: 分块 embedding 索引挂在管线尾部；索引实现内部捕获异常，与产物状态机解耦
        knowledgeIndexService.indexItem(itemId);
    }

    /** Single-artifact entry point used by the regenerate endpoint. */
    @Async("knowledgeTaskExecutor")
    public void generateOne(String itemId, ArtifactKind kind) {
        generate(itemId, kind);
    }

    private void generate(String itemId, ArtifactKind kind) {
        if (!inflight.add(itemId + ":" + kind)) {
            log.info("knowledge.artifact.skip item={} kind={} reason=in-flight", itemId, kind);
            return;
        }
        try {
            doGenerate(itemId, kind);
        } finally {
            inflight.remove(itemId + ":" + kind);
        }
    }

    private void doGenerate(String itemId, ArtifactKind kind) {
        Optional<KnowledgeItem> found = itemRepository.findById(itemId);
        if (found.isEmpty()) {
            log.warn("knowledge.artifact.skip item={} kind={} reason=item-missing", itemId, kind);
            return;
        }
        KnowledgeItem item = found.get();
        markStatus(itemId, kind, ArtifactStatus.GENERATING);
        try {
            String prompt = switch (kind) {
                case SUMMARY -> KnowledgePrompts.summaryPrompt(item.getTitle(), item.getContent());
                case MINDMAP -> KnowledgePrompts.mindmapPrompt(item.getTitle(), item.getContent());
            };
            String raw = callLlm(prompt);
            String content = KnowledgePrompts.stripCodeFence(raw);
            upsertArtifact(item, kind, content, aiConfigService.getConfig().model(), null);
            markStatus(itemId, kind, ArtifactStatus.DONE);
            log.info("knowledge.artifact.done item={} kind={}", itemId, kind);
        } catch (Exception e) {
            log.warn("knowledge.artifact.failed item={} kind={} reason={}", itemId, kind, e.toString());
            upsertArtifact(item, kind, null, null, errorMessage(e));
            markStatus(itemId, kind, ArtifactStatus.FAILED);
        }
    }

    private String callLlm(String prompt) {
        return chatGateway.call(prompt, new LlmOptions(LlmFeature.ARTIFACT_GEN));
    }

    /** Reload before writing so a concurrent update to the other artifact status is not clobbered. */
    private void markStatus(String itemId, ArtifactKind kind, ArtifactStatus status) {
        itemRepository.findById(itemId).ifPresent(item -> {
            switch (kind) {
                case SUMMARY -> item.setSummaryStatus(status);
                case MINDMAP -> item.setMindmapStatus(status);
            }
            itemRepository.save(item);
        });
    }

    /** On success updates content/model and clears error; on failure keeps old content and records the error. */
    private void upsertArtifact(KnowledgeItem item, ArtifactKind kind, String content, String model, String error) {
        KnowledgeArtifact artifact = artifactRepository.findByItemIdAndKind(item.getId(), kind)
                .orElseGet(() -> KnowledgeArtifact.builder().item(item).kind(kind).content("").build());
        if (content != null) {
            artifact.setContent(content);
            artifact.setModel(model);
        }
        artifact.setError(error);
        artifactRepository.save(artifact);
    }

    private static String errorMessage(Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
        return msg.length() > 900 ? msg.substring(0, 900) : msg;
    }
}
