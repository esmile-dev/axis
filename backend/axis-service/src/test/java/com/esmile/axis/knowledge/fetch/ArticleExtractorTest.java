package com.esmile.axis.knowledge.fetch;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ArticleExtractor}: article/main priority, density fallback,
 * title priority chain, markdown conversion, and the EXTRACT_FAILED 422 path.
 */
class ArticleExtractorTest {

    private final ArticleExtractor extractor = new ArticleExtractor();

    @Test
    void articleTag_usedAsRoot_surroundingBlocksExcluded() {
        String html = """
                <html><head><title>t</title></head><body>
                <nav><a href="/">首页导航</a></nav>
                <div id="sidebar"><p>侧边栏推广内容</p></div>
                <article>
                    <h1>文章标题</h1>
                    <p>第一段正文。</p>
                    <p>第二段正文，包含 <strong>重点</strong>。</p>
                </article>
                </body></html>
                """;

        ArticleExtractor.ExtractedArticle article = extractor.extract(html);

        assertThat(article.markdown()).contains("第一段正文").contains("**重点**");
        assertThat(article.markdown()).doesNotContain("侧边栏推广内容").doesNotContain("首页导航");
    }

    @Test
    void mainTag_usedWhenNoArticle_evenIfOtherBlockIsLonger() {
        String longText = "干扰段落文字。".repeat(80);
        String html = """
                <html><head><title>t</title></head><body>
                <main><p>主区正文内容。</p></main>
                <div id="other"><p>%s</p></div>
                </body></html>
                """.formatted(longText);

        ArticleExtractor.ExtractedArticle article = extractor.extract(html);

        assertThat(article.markdown()).contains("主区正文内容");
        assertThat(article.markdown()).doesNotContain("干扰段落文字");
    }

    @Test
    void noArticleOrMain_densityFallbackPicksBiggestTextBlock() {
        String longText = "长正文段落。".repeat(60);
        String html = """
                <html><head><title>t</title></head><body>
                <div id="links"><a href="/a">链接甲</a><a href="/b">链接乙</a></div>
                <div id="content"><p>%s</p><p>%s</p></div>
                </body></html>
                """.formatted(longText, longText);

        ArticleExtractor.ExtractedArticle article = extractor.extract(html);

        assertThat(article.markdown()).contains("长正文段落");
        assertThat(article.markdown()).doesNotContain("链接甲");
    }

    @Test
    void noiseInsideRoot_isStripped() {
        String html = """
                <html><head><title>t</title></head><body>
                <article>
                    <p>正文保留。</p>
                    <script>track();</script>
                    <nav><a href="/x">相关阅读</a></nav>
                    <footer><p>版权信息</p></footer>
                </article>
                </body></html>
                """;

        ArticleExtractor.ExtractedArticle article = extractor.extract(html);

        assertThat(article.markdown()).contains("正文保留");
        assertThat(article.markdown()).doesNotContain("track").doesNotContain("相关阅读").doesNotContain("版权信息");
    }

    @Test
    void html_convertedToMarkdown_headingsLinksBold() {
        String html = """
                <html><head><title>t</title></head><body>
                <article>
                    <h2>小节标题</h2>
                    <p>带 <a href="https://example.com/x">链接文字</a> 和 <strong>加粗</strong> 的段落。</p>
                </article>
                </body></html>
                """;

        String markdown = extractor.extract(html).markdown();

        assertThat(markdown).contains("## 小节标题");
        assertThat(markdown).contains("[链接文字](https://example.com/x)");
        assertThat(markdown).contains("**加粗**");
    }

    @Test
    void title_prefersOgTitleOverTitleTag() {
        String html = """
                <html><head>
                <meta property="og:title" content="OG 标题">
                <title>Title 标签标题</title>
                </head><body><article><p>正文内容文字。</p></article></body></html>
                """;

        assertThat(extractor.extract(html).title()).isEqualTo("OG 标题");
    }

    @Test
    void title_fallsBackToTitleTag() {
        String html = """
                <html><head><title>Title 标签标题</title></head>
                <body><article><p>正文内容文字。</p></article></body></html>
                """;

        assertThat(extractor.extract(html).title()).isEqualTo("Title 标签标题");
    }

    @Test
    void title_fallsBackToH1WhenNoOgOrTitle() {
        String html = """
                <html><head></head>
                <body><article><h1>H1 标题</h1><p>正文内容文字。</p></article></body></html>
                """;

        assertThat(extractor.extract(html).title()).isEqualTo("H1 标题");
    }

    @Test
    void emptyExtraction_throws422ExtractFailed() {
        String html = """
                <html><head><title>空页面</title></head><body>
                <script>console.log('x')</script>
                <nav><a href="/">首页</a></nav>
                </body></html>
                """;

        assertThatThrownBy(() -> extractor.extract(html))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).contains("EXTRACT_FAILED");
                });
    }
}
