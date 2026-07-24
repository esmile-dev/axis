package com.esmile.axis.repository;

import com.esmile.axis.entity.AiConfigProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiConfigProfileRepository extends JpaRepository<AiConfigProfile, String> {

    Optional<AiConfigProfile> findByActiveTrue();

    List<AiConfigProfile> findAllByOrderByCreatedAtAsc();
}
