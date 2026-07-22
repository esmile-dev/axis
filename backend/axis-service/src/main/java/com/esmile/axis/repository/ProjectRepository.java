package com.esmile.axis.repository;

import com.esmile.axis.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findAllByOrderByOrderAsc();
}
