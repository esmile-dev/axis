package com.esmile.axis.knowledge.search;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** 启动时探测当前库是否已启用 pgvector 扩展（查 pg_extension）。探测本身失败按不可用处理。 */
@Slf4j
public class PgVectorAvailability {

    private final boolean available;

    public PgVectorAvailability(DataSource dataSource) {
        this.available = probe(dataSource);
    }

    public boolean isAvailable() {
        return available;
    }

    private boolean probe(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (Exception e) {
            log.warn("knowledge.vector.probe-failed reason={}", e.toString());
            return false;
        }
    }
}
