package com.esmile.axis.project;

import com.esmile.axis.project.Comment;
import com.esmile.axis.project.Issue;
import com.esmile.axis.project.Project;
import com.esmile.axis.project.IssuePriority;
import com.esmile.axis.project.IssueStatus;
import com.esmile.axis.project.IssueType;
import com.esmile.axis.project.CommentRepository;
import com.esmile.axis.project.IssueRepository;
import com.esmile.axis.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IssueService {

    private final IssueRepository issueRepository;
    private final CommentRepository commentRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<Issue> findAll(String projectId) {
        if ("none".equals(projectId)) {
            return issueRepository.findByProjectIdIsNullOrderByOrderAsc();
        } else if (projectId != null) {
            return issueRepository.findByProjectIdOrderByOrderAsc(projectId);
        }
        return issueRepository.findAllByOrderByOrderAsc();
    }

    @Transactional(readOnly = true)
    public Issue findById(String id) {
        return issueRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Issue not found: " + id));
    }

    @Transactional
    public Issue create(String title, String description, String status, String priority,
                        String type, Integer order, String projectId, String attachment) {
        Issue issue = Issue.builder()
                .title(title)
                .description(description)
                .status(status != null ? IssueStatus.valueOf(status) : IssueStatus.TODO)
                .priority(priority != null ? IssuePriority.valueOf(priority) : IssuePriority.MEDIUM)
                .type(type != null ? IssueType.valueOf(type) : IssueType.FEATURE)
                .order(order != null ? order : 0)
                .attachment(attachment)
                .build();

        if (projectId != null) {
            Project project = projectRepository.findById(projectId).orElse(null);
            issue.setProject(project);
        }

        return issueRepository.save(issue);
    }

    @Transactional
    public Issue update(String id, String title, String description, String status,
                        String priority, String type, Integer order, String projectId,
                        String attachment) {
        Issue issue = findById(id);
        if (title != null) issue.setTitle(title);
        if (description != null) issue.setDescription(description);
        if (status != null) issue.setStatus(IssueStatus.valueOf(status));
        if (priority != null) issue.setPriority(IssuePriority.valueOf(priority));
        if (type != null) issue.setType(IssueType.valueOf(type));
        if (order != null) issue.setOrder(order);
        if (attachment != null) issue.setAttachment(attachment);
        if (projectId != null) {
            if (projectId.isEmpty()) {
                issue.setProject(null);
            } else {
                Project project = projectRepository.findById(projectId).orElse(null);
                issue.setProject(project);
            }
        }
        return issueRepository.save(issue);
    }

    @Transactional
    public void delete(String id) {
        issueRepository.deleteById(id);
    }

    // --- Comments ---

    @Transactional(readOnly = true)
    public List<Comment> findComments(String issueId) {
        return commentRepository.findByIssueIdOrderByCreatedAtAsc(issueId);
    }

    @Transactional
    public Comment addComment(String issueId, String content) {
        Issue issue = findById(issueId);
        Comment comment = Comment.builder()
                .content(content)
                .issue(issue)
                .build();
        return commentRepository.save(comment);
    }
}
