package com.esmile.axis.repository;

import com.esmile.axis.entity.InboxItem;
import com.esmile.axis.enums.InboxItemStatus;
import com.esmile.axis.enums.InboxItemType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface InboxItemRepository extends JpaRepository<InboxItem, String> {

    List<InboxItem> findAllByOrderByCreatedAtDesc();

    List<InboxItem> findByStatusOrderByCreatedAtDesc(InboxItemStatus status);

    List<InboxItem> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(Instant since);

    List<InboxItem> findByStatusAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            InboxItemStatus status, Instant since);

    List<InboxItem> findByContentContainingIgnoreCaseOrderByCreatedAtDesc(String search);

    List<InboxItem> findByStatusAndContentContainingIgnoreCaseOrderByCreatedAtDesc(
            InboxItemStatus status, String search);

    /** Retry cleanup for Daily Digest: remove a day's digest items before re-running. */
    long deleteByTypeAndDigestDate(InboxItemType type, LocalDate digestDate);
}
