package com.axis.controller;

import com.axis.entity.Project;
import com.axis.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public List<Project> list() {
        return projectService.findAll();
    }

    @PostMapping
    public Project create(@RequestBody Map<String, Object> body) {
        return projectService.create(
                (String) body.get("name"),
                (String) body.get("description"),
                (String) body.get("status"),
                body.get("order") != null ? ((Number) body.get("order")).intValue() : null
        );
    }

    @PatchMapping("/{id}")
    public Project update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return projectService.update(
                id,
                (String) body.get("name"),
                (String) body.get("description"),
                (String) body.get("status"),
                body.get("order") != null ? ((Number) body.get("order")).intValue() : null
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
