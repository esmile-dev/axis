package com.axis.repository;

import com.axis.entity.Issue;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IssueRepository extends JpaRepository<Issue, String> {

    @EntityGraph(attributePaths = {"project", "comments"})
    List<Issue> findAllByOrderByOrderAsc();

    @EntityGraph(attributePaths = {"project", "comments"})
    List<Issue> findByProjectIdOrderByOrderAsc(String projectId);

    @EntityGraph(attributePaths = {"project", "comments"})
    List<Issue> findByProjectIdIsNullOrderByOrderAsc();

    @EntityGraph(attributePaths = {"project", "comments"})
    Optional<Issue> findById(String id);

    long countByProjectId(String projectId);
}
