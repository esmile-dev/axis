package com.esmile.axis.knowledge.fetch;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link WebPageFetcher}: malformed URL becomes 422 FETCH_FAILED instead of 500. */
class WebPageFetcherTest {

    private final WebPageFetcher fetcher = new WebPageFetcher();

    @Test
    void malformedUrl_throws422FetchFailed() {
        // 能过 @Pattern 但 Jsoup connect() 拒收（IllegalArgumentException）
        assertThatThrownBy(() -> fetcher.fetch("http://[::1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).contains("FETCH_FAILED");
                });
    }
}
