package com.esmile.axis.knowledge.search;

import com.esmile.axis.llm.AiConfigService;
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

/** {@link PgVectorStoreHolder}：装配失败降级 null 不阻塞启动；reload 事件触发重建（B-004 回归）。 */
@ExtendWith(MockitoExtension.class)
class PgVectorStoreHolderTest {

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

    private void stubExtensionProbe() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
    }

    @Test
    void storeInitThrows_degradesToNull_noExceptionEscapes() throws SQLException {
        // pgvector 扩展探测为存在，但 afterPropertiesSet 建表/建索引失败（如权限不足）
        stubExtensionProbe();
        when(resultSet.getInt(1)).thenReturn(1);
        when(aiConfigService.getEmbeddingModel()).thenReturn(mock(EmbeddingModel.class));
        doThrow(new DataAccessException("permission denied") {
        }).when(jdbcTemplate).execute(anyString());

        // 构造不抛出即验证「不阻塞启动」
        PgVectorStoreHolder holder = new PgVectorStoreHolder(dataSource, jdbcTemplate, aiConfigService);

        assertThat(holder.get()).isNull();
    }

    @Test
    void rebuild_afterConfigFixed_storeBecomesAvailable() throws SQLException {
        // 初始扩展缺失 → 降级 null
        stubExtensionProbe();
        when(resultSet.getInt(1)).thenReturn(0);
        PgVectorStoreHolder holder = new PgVectorStoreHolder(dataSource, jdbcTemplate, aiConfigService);
        assertThat(holder.get()).isNull();

        // 之后扩展可用且 embedding 配置就绪，reload 事件触发重建 → 不重启即生效
        when(resultSet.getInt(1)).thenReturn(1);
        when(aiConfigService.getEmbeddingModel()).thenReturn(mock(EmbeddingModel.class));
        holder.rebuild();

        assertThat(holder.get()).isNotNull();
    }
}
