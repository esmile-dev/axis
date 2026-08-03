package com.esmile.axis.controller;

import com.esmile.axis.entity.InboxItem;
import com.esmile.axis.service.InboxService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inbox")
@RequiredArgsConstructor
public class InboxController {

    private final InboxService inboxService;

    @GetMapping
    public List<InboxItem> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String timeRange,
            @RequestParam(required = false) String search) {
        return inboxService.findAll(status, timeRange, search);
    }

    @PostMapping
    public InboxItem create(@Valid @RequestBody CreateInboxItemRequest req) {
        return inboxService.create(req.content());
    }

    @PatchMapping("/{id}")
    public InboxItem update(@PathVariable String id, @Valid @RequestBody UpdateInboxItemRequest req) {
        return inboxService.update(id, req.content(), req.status(), Boolean.TRUE.equals(req.read()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        inboxService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- DTOs ----------

    public record CreateInboxItemRequest(
            @NotBlank String content
    ) {
    }

    public record UpdateInboxItemRequest(
            String content,
            String status,
            Boolean read
    ) {
    }
}
