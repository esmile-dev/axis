package com.esmile.axis.knowledge.fetch;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Thin network layer of the URL-ingest pipeline: GETs the page HTML with a desktop
 * User-Agent and a hard 15s timeout. Any network/timeout/HTTP error becomes 422 FETCH_FAILED.
 */
@Slf4j
@Component
public class WebPageFetcher {

    static final int TIMEOUT_MS = 15_000;
    static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    public String fetch(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get()
                    .outerHtml();
        } catch (IOException e) {
            log.warn("URL fetch failed url={}: {}", url, e.toString());
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "FETCH_FAILED: " + detail);
        }
    }
}
