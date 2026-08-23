package com.esmile.axis.knowledge.search;

import com.esmile.axis.llm.AiConfigReloadedEvent;
import com.esmile.axis.llm.AiConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 持有 pgvector {@link VectorStore} 的可变引用（volatile），供检索/索引服务每次调用时获取。
 *
 * <p>启动时构建一次；收到 {@link AiConfigReloadedEvent}（embedding 档案切换/更新）后重建——
 * 修复 B-004 的"启动快照"语义。pgvector 扩展缺失或装配/重建失败时为 null（降级关键词检索），
 * 不阻塞应用。
 *
 * <p>PgVectorStore 手工构建（非 Spring Bean），需显式调 {@code afterPropertiesSet()}
 * 触发建表（initializeSchema）。
 */
@Slf4j
@Component
public class PgVectorStoreHolder {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final AiConfigService aiConfigService;

    /** null = pgvector 不可用或装配失败，走降级。 */
    private volatile VectorStore vectorStore;

    public PgVectorStoreHolder(DataSource dataSource, JdbcTemplate jdbcTemplate, AiConfigService aiConfigService) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.aiConfigService = aiConfigService;
        this.vectorStore = buildStore();
    }

    /** 当前 store；null 表示降级中。 */
    public VectorStore get() {
        return vectorStore;
    }

    /** AI 配置重载后重建 store（embedding 配置可能已变化）；失败降级为 null + WARN。 */
    @EventListener(AiConfigReloadedEvent.class)
    public synchronized void rebuild() {
        log.info("knowledge.vector.rebuild — AI 配置已重载，重建 PgVectorStore");
        this.vectorStore = buildStore();
    }

    private VectorStore buildStore() {
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
}
