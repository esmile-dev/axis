package com.esmile.axis.knowledge.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** {@link PgVectorAvailability} 启动探测：扩展存在/缺失/探测异常三态。 */
@ExtendWith(MockitoExtension.class)
class PgVectorAvailabilityTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement statement;
    @Mock
    private ResultSet resultSet;

    private void mockQuery(int count) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(count);
    }

    @Test
    void probe_extensionPresent_available() throws SQLException {
        mockQuery(1);
        assertThat(new PgVectorAvailability(dataSource).isAvailable()).isTrue();
    }

    @Test
    void probe_extensionMissing_unavailable() throws SQLException {
        mockQuery(0);
        assertThat(new PgVectorAvailability(dataSource).isAvailable()).isFalse();
    }

    @Test
    void probe_queryThrows_unavailable() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("no db"));
        assertThat(new PgVectorAvailability(dataSource).isAvailable()).isFalse();
    }
}
