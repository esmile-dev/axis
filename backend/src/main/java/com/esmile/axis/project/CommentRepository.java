package com.esmile.axis.project;

import com.esmile.axis.project.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, String> {

    List<Comment> findByIssueIdOrderByCreatedAtAsc(String issueId);
}
