package com.esmile.axis.controller;

import com.esmile.axis.entity.Comment;
import com.esmile.axis.entity.Issue;
import com.esmile.axis.service.IssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    @GetMapping
    public List<Issue> list(@RequestParam(required = false) String projectId) {
        return issueService.findAll(projectId);
    }

    @GetMapping("/{id}")
    public Issue get(@PathVariable String id) {
        return issueService.findById(id);
    }

    @PostMapping
    public Issue create(@RequestBody Map<String, Object> body) {
        return issueService.create(
                (String) body.get("title"),
                (String) body.get("description"),
                (String) body.get("status"),
                (String) body.get("priority"),
                (String) body.get("type"),
                body.get("order") != null ? ((Number) body.get("order")).intValue() : null,
                (String) body.get("projectId"),
                (String) body.get("attachment")
        );
    }

    @PatchMapping("/{id}")
    public Issue update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return issueService.update(
                id,
                (String) body.get("title"),
                (String) body.get("description"),
                (String) body.get("status"),
                (String) body.get("priority"),
                (String) body.get("type"),
                body.get("order") != null ? ((Number) body.get("order")).intValue() : null,
                (String) body.get("projectId"),
                (String) body.get("attachment")
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        issueService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Comments ---

    @GetMapping("/{id}/comments")
    public List<Comment> getComments(@PathVariable String id) {
        return issueService.findComments(id);
    }

    @PostMapping("/{id}/comments")
    public Comment addComment(@PathVariable String id, @RequestBody Map<String, String> body) {
        return issueService.addComment(id, body.get("content"));
    }
}
