package com.axis.digest.fetch;

import com.axis.digest.classify.DigestCategory;

import java.time.Instant;

/**
 * Parsed RSS article awaiting classification and rendering.
 * The {@code category} field is set by a {@link com.axis.digest.classify.Classifier}.
 */
public record Article(
        String title,
        String link,
        String summary,
        String sourceName,
        Instant publishedAt,
        DigestCategory category
) {
    /** Copy with the given category — used after classification. */
    public Article withCategory(DigestCategory c) {
        return new Article(title, link, summary, sourceName, publishedAt, c);
    }
}