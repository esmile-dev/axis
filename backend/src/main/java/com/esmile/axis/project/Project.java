package com.esmile.axis.project;

import com.esmile.axis.project.ProjectStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "project")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Project {

    @Id
    @Column(length = 30)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String repoPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.PLANNING;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer order = 0;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @JsonIgnore
    @JsonIgnoreProperties({"project", "comments", "hibernateLazyInitializer"})
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Issue> issues = new ArrayList<>();

    @Transient
    private Integer issueCount;

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
