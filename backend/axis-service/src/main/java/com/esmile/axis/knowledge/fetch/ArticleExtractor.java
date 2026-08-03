package com.esmile.axis.knowledge.fetch;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure extraction step of the URL-ingest pipeline: HTML string in, {@code {title, markdown}} out.
 * Content root is article/main first, otherwise the block with the highest paragraph-text
 * density. Empty extraction becomes 422 EXTRACT_FAILED.
 */
@Component
public class ArticleExtractor {

    private static final String NOISE_TAGS = "script, style, noscript, iframe, nav, aside, form, button, select, header, footer";

    public record ExtractedArticle(String title, String markdown) {
    }

    // ATX headings (# style) everywhere: setext only supports two levels and breaks
    // downstream heading-level processing (LLM artifacts / mindmap outline checks).
    private final FlexmarkHtmlConverter htmlConverter = FlexmarkHtmlConverter
            .builder(new MutableDataSet().set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false))
            .build();

    public ExtractedArticle extract(String html) {
        Document doc = Jsoup.parse(html);
        String title = extractTitle(doc);
        Element root = pickContentRoot(doc);
        root.select(NOISE_TAGS).remove();
        String markdown = htmlConverter.convert(root.outerHtml()).trim();
        if (markdown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXTRACT_FAILED: 未能从页面提取正文，请改用粘贴创建");
        }
        return new ExtractedArticle(title, markdown);
    }

    private String extractTitle(Document doc) {
        Element ogTitle = doc.selectFirst("meta[property=og:title]");
        if (ogTitle != null && !ogTitle.attr("content").isBlank()) {
            return ogTitle.attr("content").trim();
        }
        if (!doc.title().isBlank()) {
            return doc.title().trim();
        }
        Element h1 = doc.selectFirst("h1");
        return h1 != null && !h1.text().isBlank() ? h1.text().trim() : "未命名文章";
    }

    private Element pickContentRoot(Document doc) {
        Element article = doc.selectFirst("article");
        if (article != null) {
            return article;
        }
        Element main = doc.selectFirst("main");
        if (main != null) {
            return main;
        }
        // Density fallback: score = pTextLen^2 / htmlLen, so a big coherent text block beats
        // both its own paragraph wrappers and the noisier outer containers.
        Element best = doc.body();
        double bestScore = 0;
        for (Element el : doc.select("div, section")) {
            long pTextLen = el.select("p").stream().mapToLong(p -> p.text().length()).sum();
            double score = pTextLen * (double) pTextLen / el.html().length();
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        return best;
    }
}
