package com.esmile.axis.project;

import com.esmile.axis.project.Project;
import com.esmile.axis.project.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public Project create(@Valid @RequestBody CreateProjectRequest req) {
        return projectService.create(req.name(), req.description(), req.status(), req.order());
    }

    @PatchMapping("/{id}")
    public Project update(@PathVariable String id, @Valid @RequestBody UpdateProjectRequest req) {
        return projectService.update(id, req.name(), req.description(), req.status(), req.order(), req.repoPath());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- DTOs ----------

    public record CreateProjectRequest(
            @NotBlank String name,
            String description,
            String status,
            Integer order
    ) {
    }

    public record UpdateProjectRequest(
            String name,
            String description,
            String status,
            Integer order,
            String repoPath
    ) {
    }
}
