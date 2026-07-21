package com.axis.digest.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface DigestExecutionLogRepository extends JpaRepository<DigestExecutionLog, String> {

    /** Look up today's execution row (no lock — single-instance deployment). */
    Optional<DigestExecutionLog> findByDigestDate(LocalDate date);
}
