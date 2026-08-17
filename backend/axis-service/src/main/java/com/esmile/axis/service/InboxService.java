package com.esmile.axis.service;

import com.esmile.axis.entity.InboxItem;
import com.esmile.axis.enums.InboxItemStatus;
import com.esmile.axis.repository.InboxItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InboxService {

    private final InboxItemRepository repository;

    public List<InboxItem> findAll(String status, String timeRange, String search) {
        // Build dynamic query based on filters
        // Priority: search > status + timeRange > status > timeRange > all
        if (search != null && !search.isBlank()) {
            if (status != null && !"all".equals(status)) {
                return repository.findByStatusAndContentContainingIgnoreCaseOrderByCreatedAtDesc(
                        InboxItemStatus.valueOf(status), search.trim());
            }
            return repository.findByContentContainingIgnoreCaseOrderByCreatedAtDesc(search.trim());
        }

        Instant since = resolveTimeRange(timeRange);

        if (status != null && !"all".equals(status)) {
            InboxItemStatus statusEnum = InboxItemStatus.valueOf(status);
            if (since != null) {
                return repository.findByStatusAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(statusEnum, since);
            }
            return repository.findByStatusOrderByCreatedAtDesc(statusEnum);
        }

        if (since != null) {
            return repository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since);
        }

        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public InboxItem create(String content) {
        InboxItem item = InboxItem.builder()
                .content(content)
                .status(InboxItemStatus.TODO)
                .build();
        return repository.save(item);
    }

    @Transactional(readOnly = true)
    public InboxItem findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("InboxItem not found: " + id));
    }

    @Transactional
    public InboxItem update(String id, String content, String status, boolean read) {
        InboxItem item = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("InboxItem not found: " + id));
        if (content != null) {
            item.setContent(content);
        }
        if (status != null) {
            item.setStatus(InboxItemStatus.valueOf(status));
        }
        // 已读标记：只置一次，不覆盖（「看过」不可逆）
        if (read && item.getReadAt() == null) {
            item.setReadAt(Instant.now());
        }
        return repository.save(item);
    }

    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }

    private Instant resolveTimeRange(String timeRange) {
        if (timeRange == null || "all".equals(timeRange)) {
            return null;
        }
        Instant now = Instant.now();
        return switch (timeRange) {
            case "today" -> now.truncatedTo(ChronoUnit.DAYS);
            case "week" -> now.minus(7, ChronoUnit.DAYS);
            case "month" -> now.minus(30, ChronoUnit.DAYS);
            default -> null;
        };
    }
}
