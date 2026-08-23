package com.esmile.axis.llm;

import com.esmile.axis.llm.AiConfigProfile;
import com.esmile.axis.llm.AiProfileType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiConfigProfileRepository extends JpaRepository<AiConfigProfile, String> {

    Optional<AiConfigProfile> findByActiveTrueAndType(AiProfileType type);

    List<AiConfigProfile> findByTypeOrderByCreatedAtAsc(AiProfileType type);

    long countByType(AiProfileType type);

    List<AiConfigProfile> findAllByOrderByCreatedAtAsc();
}
