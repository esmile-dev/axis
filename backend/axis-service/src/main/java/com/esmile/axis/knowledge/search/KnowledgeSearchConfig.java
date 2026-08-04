package com.esmile.axis.knowledge.search;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * FR-010 检索装配：启动时探测 pgvector —— 有扩展则构建 PgVectorStore（EmbeddingModel
 * 与 ChatClient 同配置源），装配向量检索/索引；无扩展则降级关键词检索 + 空索引，
 * 并 WARN 写明降级原因。扩展存在但 store 初始化（建表/建索引）失败时同样降级，
 * 不阻塞应用启动。
 *
 * <p>PgVectorStore 手工构建（非 Spring Bean），需显式调 {@code afterPropertiesSet()}
 * 触发建表（initializeSchema）。自动配置（spring.ai.vectorstore.type=none）已关闭，
 * 避免它用 env 兜底的 EmbeddingModel 再建一个 store。
 */
@Slf4j
@Configuration
public class KnowledgeSearchConfig {

    /** null = pgvector 不可用或装配失败，走降级。 */
    private final VectorStore vectorStore;

    public KnowledgeSearchConfig(DataSource dataSource, JdbcTemplate jdbcTemplate, AiConfigService aiConfigService) {
        this.vectorStore = buildStore(dataSource, jdbcTemplate, aiConfigService);
    }

    private VectorStore buildStore(DataSource dataSource, JdbcTemplate jdbcTemplate, AiConfigService aiConfigService) {
        if (!new PgVectorAvailability(dataSource).isAvailable()) {
            log.warn("knowledge.vector.degraded reason=pgvector-extension-missing — 知识检索降级为 DB 关键词匹配");
            return null;
        }
        try {
            PgVectorStore store = PgVectorStore.builder(jdbcTemplate, aiConfigService.getEmbeddingModel())
                    .initializeSchema(true)
                    .build();
            store.afterPropertiesSet();
            log.info("knowledge.vector.enabled — pgvector 已就绪，语义检索装配完成");
            return store;
        } catch (Exception e) {
            log.warn("knowledge.vector.degraded reason=store-init-failed cause={} — 知识检索降级为 DB 关键词匹配", e.toString());
            return null;
        }
    }

    @Bean
    public KnowledgeSearchService knowledgeSearchService(KnowledgeItemRepository itemRepository) {
        KeywordKnowledgeSearchService keyword = new KeywordKnowledgeSearchService(itemRepository);
        return vectorStore != null ? new VectorKnowledgeSearchService(vectorStore, keyword) : keyword;
    }

    @Bean
    public KnowledgeIndexService knowledgeIndexService(KnowledgeItemRepository itemRepository) {
        return vectorStore != null
                ? new VectorKnowledgeIndexService(vectorStore, itemRepository)
                : new NoopKnowledgeIndexService();
    }
}
