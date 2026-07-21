package com.axis.controller;

import com.axis.entity.InboxItem;
import com.axis.service.InboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    public InboxItem create(@RequestBody Map<String, String> body) {
        return inboxService.create(body.get("content"));
    }

    @PatchMapping("/{id}")
    public InboxItem update(@PathVariable String id, @RequestBody Map<String, String> body) {
        return inboxService.update(id, body.get("content"), body.get("status"),
                "true".equalsIgnoreCase(body.get("read")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        inboxService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
