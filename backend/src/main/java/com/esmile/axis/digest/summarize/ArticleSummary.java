package com.esmile.axis.digest.summarize;

import com.esmile.axis.inbox.DigestCategory;

import java.util.List;

/**
 * Structured result of the LLM fine-read step for one article.
 */
public record ArticleSummary(
        String headline,
        String tldr,
        String detail,
        String whyItMatters,
        String source,
        String url,
        DigestCategory category
) {
}
