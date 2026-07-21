package com.axis.digest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Typed binding for {@code app.digest.*} configuration keys.
 * All defaults are supplied via {@code application.yml}; missing keys fail fast.
 */
@ConfigurationProperties(prefix = "app.digest")
public record DigestProperties(
        String cron,
        List<RssSourceConfig> rssSources,
        ClassifierConfig classifier,
        TimeoutConfig timeout,
        int threadPoolSize
) {
    public record RssSourceConfig(String name, String url) {}

    public record ClassifierConfig(String impl, KeywordsConfig keywords) {
        public record KeywordsConfig(
                List<String> aiFrontier,
                List<String> techIndustry,
                List<String> financeTech
        ) {}
    }

    public record TimeoutConfig(int perSourceSeconds, int totalSeconds) {}
}