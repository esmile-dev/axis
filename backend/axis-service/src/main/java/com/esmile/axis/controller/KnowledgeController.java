package com.esmile.axis.controller;

import com.esmile.axis.entity.KnowledgeDocument;
import com.esmile.axis.service.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public List<KnowledgeDocument> list() {
        return knowledgeService.findAll();
    }

    @PostMapping
    public KnowledgeDocument create(@Valid @RequestBody CreateKnowledgeDocRequest req) {
        return knowledgeService.create(req.title(), req.content());
    }

    @PatchMapping("/{id}")
    public KnowledgeDocument update(@PathVariable String id, @Valid @RequestBody UpdateKnowledgeDocRequest req) {
        return knowledgeService.update(id, req.title(), req.content());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        knowledgeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- DTOs ----------

    public record CreateKnowledgeDocRequest(
            @NotBlank String title,
            String content
    ) {
    }

    public record UpdateKnowledgeDocRequest(
            String title,
            String content
    ) {
    }
}
