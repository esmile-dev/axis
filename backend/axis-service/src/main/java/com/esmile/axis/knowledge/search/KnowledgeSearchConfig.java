package com.esmile.axis.knowledge.search;

import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FR-010 检索装配：检索/索引服务经 {@link PgVectorStoreHolder} 动态获取 store
 * （每次调用取当前值），因此 AI 配置重载后的 store 重建即时生效，无需重启。
 * store 为 null（pgvector 缺失/装配失败）时服务内部降级关键词检索/空索引 + WARN。
 *
 * <p>自动配置（spring.ai.vectorstore.type=none）已关闭，避免它用 env 兜底的
 * EmbeddingModel 再建一个 store。
 */
@Configuration
public class KnowledgeSearchConfig {

    @Bean
    public KnowledgeSearchService knowledgeSearchService(KnowledgeItemRepository itemRepository, PgVectorStoreHolder holder) {
        return new VectorKnowledgeSearchService(holder::get, new KeywordKnowledgeSearchService(itemRepository));
    }

    @Bean
    public KnowledgeIndexService knowledgeIndexService(KnowledgeItemRepository itemRepository, PgVectorStoreHolder holder) {
        return new VectorKnowledgeIndexService(holder::get, itemRepository);
    }
}
