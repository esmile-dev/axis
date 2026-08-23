package com.esmile.axis.project;

import com.esmile.axis.project.Project;
import com.esmile.axis.project.IssueRepository;
import com.esmile.axis.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * repo_path 更新语义：非空 trim 写入、空白清为 null、null 不动原值。
 */
class ProjectServiceTest {

    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final IssueRepository issueRepository = mock(IssueRepository.class);

    private final ProjectService service = new ProjectService(repository, issueRepository);

    private Project stubProject(String repoPath) {
        Project project = Project.builder().id("p1").name("axis").repoPath(repoPath).build();
        when(repository.findById("p1")).thenReturn(Optional.of(project));
        when(repository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
        return project;
    }

    @Test
    void updateSetsRepoPath() {
        Project project = stubProject(null);
        service.update("p1", null, null, null, null, " /tmp/repo ");
        assertThat(project.getRepoPath()).isEqualTo("/tmp/repo");
    }

    @Test
    void updateBlankRepoPathClearsToNull() {
        Project project = stubProject("/tmp/repo");
        service.update("p1", null, null, null, null, "  ");
        assertThat(project.getRepoPath()).isNull();
    }

    @Test
    void updateNullRepoPathLeavesUntouched() {
        Project project = stubProject("/tmp/repo");
        service.update("p1", null, null, null, null, null);
        assertThat(project.getRepoPath()).isEqualTo("/tmp/repo");
    }
}
