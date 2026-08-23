package com.esmile.axis.digest.summarize;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ArticleSummaryCacheRepository extends JpaRepository<ArticleSummaryCache, String> {

    /** Lookup by natural composite key — caller compares model + promptVersion. */
    Optional<ArticleSummaryCache> findByLink(String link);
}
