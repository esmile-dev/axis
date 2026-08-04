package com.esmile.axis.knowledge.search;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link KnowledgeSearchConfig} 装配兜底：扩展存在但 store 初始化失败时降级关键词检索，不阻塞启动。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeSearchConfigTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement statement;
    @Mock
    private ResultSet resultSet;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private AiConfigService aiConfigService;

    @Test
    void storeInitThrows_degradesToKeyword_noExceptionEscapes() throws SQLException {
        // pgvector 扩展探测为存在
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(1);
        // afterPropertiesSet 建表/建索引失败（如权限不足）
        when(aiConfigService.getEmbeddingModel()).thenReturn(mock(EmbeddingModel.class));
        doThrow(new DataAccessException("permission denied") {
        }).when(jdbcTemplate).execute(anyString());

        // 构造不抛出即验证「不阻塞启动」
        KnowledgeSearchConfig config = new KnowledgeSearchConfig(dataSource, jdbcTemplate, aiConfigService);

        assertThat(config.knowledgeSearchService(mock(KnowledgeItemRepository.class)))
                .isInstanceOf(KeywordKnowledgeSearchService.class);
        assertThat(config.knowledgeIndexService(mock(KnowledgeItemRepository.class)))
                .isInstanceOf(NoopKnowledgeIndexService.class);
    }
}
