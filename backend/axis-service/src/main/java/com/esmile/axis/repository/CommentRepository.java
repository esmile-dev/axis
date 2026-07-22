package com.esmile.axis.repository;

import com.esmile.axis.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, String> {

    List<Comment> findByIssueIdOrderByCreatedAtAsc(String issueId);
}
