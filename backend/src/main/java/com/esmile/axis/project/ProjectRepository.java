package com.esmile.axis.project;

import com.esmile.axis.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findAllByOrderByOrderAsc();
}
