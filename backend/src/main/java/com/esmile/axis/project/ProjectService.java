package com.esmile.axis.project;

import com.esmile.axis.project.Project;
import com.esmile.axis.project.ProjectStatus;
import com.esmile.axis.project.IssueRepository;
import com.esmile.axis.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository repository;
    private final IssueRepository issueRepository;

    @Transactional(readOnly = true)
    public List<Project> findAll() {
        List<Project> projects = repository.findAllByOrderByOrderAsc();
        projects.forEach(p -> p.setIssueCount((int) issueRepository.countByProjectId(p.getId())));
        return projects;
    }

    @Transactional(readOnly = true)
    public Project findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Project not found: " + id));
    }

    @Transactional
    public Project create(String name, String description, String status, Integer order) {
        Project project = Project.builder()
                .name(name)
                .description(description)
                .status(status != null ? ProjectStatus.valueOf(status) : ProjectStatus.PLANNING)
                .order(order != null ? order : 0)
                .build();
        return repository.save(project);
    }

    @Transactional
    public Project update(String id, String name, String description, String status, Integer order, String repoPath) {
        Project project = findById(id);
        if (name != null) project.setName(name);
        if (description != null) project.setDescription(description);
        if (status != null) project.setStatus(ProjectStatus.valueOf(status));
        if (order != null) project.setOrder(order);
        if (repoPath != null) project.setRepoPath(repoPath.isBlank() ? null : repoPath.trim());
        return repository.save(project);
    }

    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }
}
