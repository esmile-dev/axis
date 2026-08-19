package com.esmile.axis.llm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, String> {

    /** 用量聚合的原始取数：数据量为个人级（日均百条内），窗口内明细在内存聚合。 */
    List<LlmCallLog> findByCreatedAtAfterOrderByCreatedAtDesc(Instant since);
}
