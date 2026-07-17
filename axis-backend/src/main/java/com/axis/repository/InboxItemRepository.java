package com.axis.repository;

import com.axis.entity.InboxItem;
import com.axis.enums.InboxItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
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
}
