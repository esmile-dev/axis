package com.esmile.axis.knowledge.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code app.knowledge.search.*} configuration keys.
 * All defaults are supplied via {@code application.yml}; missing keys fail fast.
 */
@ConfigurationProperties(prefix = "app.knowledge.search")
public record KnowledgeSearchProperties(
        int vectorTopK,
        int keywordTopK,
        double similarityThreshold,
        RerankConfig rerank
) {
    public record RerankConfig(boolean enabled) {}
}
