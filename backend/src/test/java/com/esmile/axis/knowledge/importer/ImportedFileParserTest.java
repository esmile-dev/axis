package com.esmile.axis.knowledge.importer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ImportedFileParser}: md/txt passthrough, pdf extraction
 * (fixture PDF generated on the fly with PDFBox), blank extraction -> 422
 * EXTRACT_FAILED, and unknown/missing extensions -> 415.
 */
class ImportedFileParserTest {

    private final ImportedFileParser parser = new ImportedFileParser();

    @Test
    void parse_markdownFile_passesTextThrough() {
        ImportedFileParser.ParsedFile parsed = parser.parse("笔记.md", "# 标题\n正文内容\n".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.extension()).isEqualTo(".md");
        assertThat(parsed.text()).isEqualTo("# 标题\n正文内容");
    }

    @Test
    void parse_txtFile_passesTextThrough() {
        ImportedFileParser.ParsedFile parsed = parser.parse("notes.txt", "plain text".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.extension()).isEqualTo(".txt");
        assertThat(parsed.text()).isEqualTo("plain text");
    }

    @Test
    void parse_uppercaseExtension_accepted() {
        ImportedFileParser.ParsedFile parsed = parser.parse("README.MD", "content".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.extension()).isEqualTo(".md");
        assertThat(parsed.text()).isEqualTo("content");
    }

    @Test
    void parse_pdfWithText_extractsText() throws IOException {
        byte[] pdf = pdfWithText("Hello PDFBox Import");

        ImportedFileParser.ParsedFile parsed = parser.parse("doc.pdf", pdf);

        assertThat(parsed.extension()).isEqualTo(".pdf");
        assertThat(parsed.text()).contains("Hello PDFBox Import");
    }

    @Test
    void parse_blankPdf_throws422ExtractFailed() throws IOException {
        byte[] pdf = blankPdf();

        assertThatThrownBy(() -> parser.parse("scanned.pdf", pdf))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).contains("EXTRACT_FAILED");
                });
    }

    @Test
    void parse_emptyMarkdown_throws422ExtractFailed() {
        assertThatThrownBy(() -> parser.parse("empty.md", "  \n ".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).contains("EXTRACT_FAILED");
                });
    }

    @Test
    void parse_unknownExtension_throws415() {
        assertThatThrownBy(() -> parser.parse("evil.exe", "MZ".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void parse_missingOrExtensionlessFilename_throws415() {
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(null, bytes))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        assertThatThrownBy(() -> parser.parse("README", bytes))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    private static byte[] pdfWithText(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] blankPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
