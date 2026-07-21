package com.axis.digest.fetch;

/**
 * One RSS feed source. Immutable.
 */
public record RssSource(String name, String url) {}