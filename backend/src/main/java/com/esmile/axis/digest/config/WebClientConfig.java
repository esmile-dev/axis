package com.esmile.axis.digest.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Shared {@link WebClient.Builder} with a sensible User-Agent and global response timeout.
 * Per-source timeouts are enforced at the call site (5s).
 */
@Configuration
@EnableConfigurationProperties(DigestProperties.class)
public class WebClientConfig {

    private static final String USER_AGENT = "AxisDailyDigest/1.0 (+https://github.com/axis)";

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)); // 2 MB RSS limit
    }

    /** Total HTTP exchange timeout (covers connect + read). Per-source timeout is still applied via .timeout(). */
    public static Duration responseTimeout() {
        return Duration.ofSeconds(10);
    }
}