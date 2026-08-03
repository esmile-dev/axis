package com.esmile.axis.knowledge;

import com.esmile.axis.knowledge.dto.CreateKnowledgeItemRequest;
import com.esmile.axis.knowledge.dto.FetchKnowledgeRequest;
import com.esmile.axis.knowledge.dto.KnowledgeItemDetailView;
import com.esmile.axis.knowledge.dto.KnowledgeItemSummaryView;
import com.esmile.axis.knowledge.dto.UpdateKnowledgeItemRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public List<KnowledgeItemSummaryView> list(
            @RequestParam(required = false) KnowledgeType type,
            @RequestParam(required = false) KnowledgeStatus status,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q) {
        return knowledgeService.list(type, status, tag, q);
    }

    @GetMapping("/{id}")
    public KnowledgeItemDetailView get(@PathVariable String id) {
        return knowledgeService.get(id);
    }

    @PostMapping
    public KnowledgeItemDetailView create(@Valid @RequestBody CreateKnowledgeItemRequest req) {
        return knowledgeService.create(req);
    }

    @PostMapping("/fetch")
    public KnowledgeItemDetailView fetch(@Valid @RequestBody FetchKnowledgeRequest req) {
        return knowledgeService.createFromUrl(req.url());
    }

    @PostMapping("/import")
    public KnowledgeItemDetailView importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) KnowledgeType type,
            @RequestParam(required = false) String title) {
        return knowledgeService.createFromImport(file, type, title);
    }

    @PatchMapping("/{id}")
    public KnowledgeItemDetailView update(@PathVariable String id, @Valid @RequestBody UpdateKnowledgeItemRequest req) {
        return knowledgeService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        knowledgeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
