package com.esmile.axis.knowledge.chat;

import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.llm.ChatGateway.LlmOptions;
import com.esmile.axis.llm.LlmFeature;
import com.esmile.axis.knowledge.ArtifactKind;
import com.esmile.axis.knowledge.entity.KnowledgeArtifact;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeArtifactRepository;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Source-grounded QA over a single knowledge item (design.md §6, FR-009).
 * Read-only: no tools are attached and the item is never modified. Conversation
 * history is kept per item via chat memory under
 * {@code conversationId = "knowledge-" + itemId}, so two items never share context.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeQaService {

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeArtifactRepository artifactRepository;
    private final ChatGateway chatGateway;

    /**
     * Stream the answer tokens. The 404 is thrown synchronously (before the Flux is
     * returned), so the controller responds 404 instead of opening an SSE stream.
     */
    public Flux<String> chat(String itemId, String message) {
        KnowledgeItem item = findOrThrow(itemId);
        String summary = artifactRepository.findByItemIdAndKind(itemId, ArtifactKind.SUMMARY)
                .map(KnowledgeArtifact::getContent)
                .filter(s -> !s.isBlank())
                .orElse(null);
        return chatGateway.stream(
                KnowledgeQaPrompts.qaSystemPrompt(item.getTitle(), summary, item.getContent()),
                message,
                new LlmOptions(conversationId(itemId), LlmFeature.KNOWLEDGE_QA));
    }

    /** Conversation id rule shared with the frontend history loader and the eval. */
    public static String conversationId(String itemId) {
        return "knowledge-" + itemId;
    }

    private KnowledgeItem findOrThrow(String id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge item not found: " + id));
    }
}
