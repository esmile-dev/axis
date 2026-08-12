package com.esmile.axis.digest.fetch;

import com.esmile.axis.digest.config.DigestProperties;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fetches a single RSS feed and parses it via the Rome library.
 * Returns an empty list on any failure (logged as a warning) — never throws.
 */
@Slf4j
@Component
public class RssFetcher {

    private final WebClient.Builder webClientBuilder;
    private final Duration perSourceTimeout;

    public RssFetcher(WebClient.Builder webClientBuilder, DigestProperties props) {
        this.webClientBuilder = webClientBuilder;
        this.perSourceTimeout = Duration.ofSeconds(props.timeout().perSourceSeconds());
    }

    /**
     * Fetch one feed and parse all entries. Returns {@code List.of()} on any error.
     */
    public List<Article> fetch(RssSource source) {
        try {
            String xml = webClientBuilder.build()
                    .get()
                    .uri(source.url())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(perSourceTimeout)
                    .onErrorResume(e -> {
                        log.warn("RSS fetch failed for {} ({}): {}", source.name(), source.url(), e.toString());
                        return Mono.empty();
                    })
                    .block();

            if (xml == null || xml.isBlank()) return List.of();

            return parseXml(source, xml);
        } catch (Exception e) {
            log.warn("RSS fetch threw for {} ({}): {}", source.name(), source.url(), e.toString());
            return List.of();
        }
    }

    private List<Article> parseXml(RssSource source, String xml) {
        try (var reader = new XmlReader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))) {
            SyndFeed feed = new SyndFeedInput().build(reader);
            return feed.getEntries().stream()
                    .map(e -> toArticle(source, e))
                    .filter(a -> a.title() != null && !a.title().isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("RSS parse failed for {} ({}): {}", source.name(), source.url(), e.toString());
            return List.of();
        }
    }

    private static Article toArticle(RssSource source, SyndEntry entry) {
        String title = stripHtml(entry.getTitle());
        String link = entry.getLink();
        String summary = stripHtml(entry.getDescription() != null ? entry.getDescription().getValue() : null);
        // Cap at 4000 per digest-2.0 design §6.1 (LLM fine-read input); display/fallback paths truncate to 240 themselves.
        if (summary.length() > 4000) summary = summary.substring(0, 3997) + "...";
        Instant publishedAt = entry.getPublishedDate() != null
                ? entry.getPublishedDate().toInstant()
                : (entry.getUpdatedDate() != null ? entry.getUpdatedDate().toInstant() : Instant.now());
        return new Article(title, link, summary, source.name(), publishedAt, null);
    }

    /** Strip simple HTML tags. Sufficient for RSS descriptions; not a full sanitizer. */
    private static String stripHtml(String input) {
        if (input == null) return "";
        return input.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
    }
}