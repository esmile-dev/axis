package com.axis.digest.store;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface DigestExecutionLogRepository extends JpaRepository<DigestExecutionLog, String> {

    /**
     * Acquire a row-level write lock on today's row (creating nothing if absent).
     * Returns empty when no digest has been started today.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from DigestExecutionLog l where l.digestDate = :date")
    Optional<DigestExecutionLog> findByDigestDateForUpdate(@Param("date") LocalDate date);

    /** Read-only lookup used by GET endpoints (no lock acquisition). */
    Optional<DigestExecutionLog> findByDigestDate(LocalDate date);
}