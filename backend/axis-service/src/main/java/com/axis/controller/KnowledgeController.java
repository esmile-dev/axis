package com.axis.controller;

import com.axis.entity.KnowledgeDocument;
import com.axis.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    public KnowledgeDocument create(@RequestBody Map<String, String> body) {
        return knowledgeService.create(body.get("title"), body.get("content"));
    }

    @PatchMapping("/{id}")
    public KnowledgeDocument update(@PathVariable String id, @RequestBody Map<String, String> body) {
        return knowledgeService.update(id, body.get("title"), body.get("content"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        knowledgeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
