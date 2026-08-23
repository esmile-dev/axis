package com.esmile.axis.project;

import com.esmile.axis.project.IssuePriority;
import com.esmile.axis.project.IssueStatus;
import com.esmile.axis.project.IssueType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "issue")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Issue {

    @Id
    @Column(length = 30)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private IssueStatus status = IssueStatus.TODO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private IssuePriority priority = IssuePriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private IssueType type = IssueType.FEATURE;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer order = 0;

    @Column(name = "project_id", insertable = false, updatable = false)
    private String projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    @JsonIgnoreProperties({"issues", "hibernateLazyInitializer"})
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Project project;

    @Column(columnDefinition = "TEXT")
    private String attachment;

    @OneToMany(mappedBy = "issue", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    @Builder.Default
    @JsonIgnoreProperties({"issue", "hibernateLazyInitializer"})
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Comment> comments = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = generateCuid();
        }
    }

    private static String generateCuid() {
        long timestamp = System.currentTimeMillis();
        String random = Long.toString(Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()), 36);
        return "c" + Long.toString(timestamp, 36) + random.substring(0, Math.min(8, random.length()));
    }
}
