package com.esmile.axis.knowledge.generate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Kicks off artifact generation once the creating transaction has committed,
 * so a rolled-back ingest never triggers LLM work.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeGenerationListener {

    private final KnowledgeArtifactGenerator generator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onItemCreated(KnowledgeItemCreatedEvent event) {
        generator.generateAll(event.itemId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegenerationRequested(KnowledgeArtifactRegenerationEvent event) {
        generator.generateOne(event.itemId(), event.kind());
    }
}
