package com.esmile.axis.digest.fetch;

import com.esmile.axis.digest.config.DigestProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Fetches all configured RSS sources in parallel, isolated per source.
 * Per-source failures are swallowed (logged); the overall call returns whatever succeeded
 * within the configured total timeout.
 */
@Slf4j
@Service
public class RssFetcherService {

    private final RssFetcher fetcher;
    private final Executor executor;
    private final Duration totalTimeout;
    private final List<RssSource> sources;

    public RssFetcherService(RssFetcher fetcher,
                             @Qualifier("digestExecutor") Executor executor,
                             DigestProperties props) {
        this.fetcher = fetcher;
        this.executor = executor;
        this.totalTimeout = Duration.ofSeconds(props.timeout().totalSeconds());
        this.sources = props.rssSources().stream()
                .map(cfg -> new RssSource(cfg.name(), cfg.url()))
                .toList();
    }

    /** Fetch all configured sources in parallel. */
    public List<Article> fetchAll() {
        return fetchAll(sources);
    }

    /** Visible for testing — accepts an explicit list of sources. */
    public List<Article> fetchAll(List<RssSource> sourceList) {
        if (sourceList.isEmpty()) return List.of();

        List<CompletableFuture<List<Article>>> futures = sourceList.stream()
                .map(src -> CompletableFuture
                        .supplyAsync(() -> fetcher.fetch(src), executor)
                        .handle((result, ex) -> {
                            if (ex != null) {
                                log.warn("Source {} raised: {}", src.name(), ex.toString());
                                return List.<Article>of();
                            }
                            return result == null ? List.<Article>of() : result;
                        }))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(totalTimeout.toSeconds(), TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Overall digest fetch exceeded {} timeout: {}", totalTimeout, e.toString());
        }

        List<Article> all = new ArrayList<>();
        for (CompletableFuture<List<Article>> f : futures) {
            try {
                all.addAll(f.getNow(List.of()));
            } catch (Exception ignored) {
                // Already handled in .handle() above.
            }
        }
        log.info("Fetched {} articles from {} sources", all.size(), sourceList.size());
        return all;
    }
}