package com.esmile.axis.controller;

import com.esmile.axis.entity.Comment;
import com.esmile.axis.entity.Issue;
import com.esmile.axis.service.IssueService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public Issue create(@Valid @RequestBody CreateIssueRequest req) {
        return issueService.create(
                req.title(), req.description(), req.status(), req.priority(),
                req.type(), req.order(), req.projectId(), req.attachment()
        );
    }

    @PatchMapping("/{id}")
    public Issue update(@PathVariable String id, @Valid @RequestBody UpdateIssueRequest req) {
        return issueService.update(
                id, req.title(), req.description(), req.status(), req.priority(),
                req.type(), req.order(), req.projectId(), req.attachment()
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
    public Comment addComment(@PathVariable String id, @Valid @RequestBody AddCommentRequest req) {
        return issueService.addComment(id, req.content());
    }

    // ---------- DTOs ----------

    public record CreateIssueRequest(
            @NotBlank String title,
            String description,
            String status,
            String priority,
            String type,
            Integer order,
            String projectId,
            String attachment
    ) {
    }

    public record UpdateIssueRequest(
            String title,
            String description,
            String status,
            String priority,
            String type,
            Integer order,
            String projectId,
            String attachment
    ) {
    }

    public record AddCommentRequest(
            @NotBlank String content
    ) {
    }
}
