package com.esmile.axis.knowledge.importer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Pure parsing step of the file-import pipeline: filename + raw bytes in,
 * {@code {extension, text}} out. .md/.txt are read as UTF-8 verbatim; .pdf goes
 * through PDFBox {@link PDFTextStripper}. Empty extraction (e.g. a scanned PDF
 * without a text layer) becomes 422 EXTRACT_FAILED; extensions outside the
 * allowlist become 415.
 */
@Component
public class ImportedFileParser {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".md", ".txt", ".pdf");

    public record ParsedFile(String extension, String text) {
    }

    public ParsedFile parse(String originalFilename, byte[] bytes) {
        String extension = resolveExtension(originalFilename);
        String text = extension.equals(".pdf")
                ? extractPdfText(bytes)
                : new String(bytes, StandardCharsets.UTF_8);
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXTRACT_FAILED: 未能从文件提取文本（扫描件 PDF 请改用粘贴创建）");
        }
        return new ParsedFile(extension, trimmed);
    }

    private String resolveExtension(String originalFilename) {
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        }
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE: 仅支持 .md/.txt/.pdf 文件导入");
        }
        return extension;
    }

    private String extractPdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXTRACT_FAILED: PDF 解析失败（文件损坏或已加密）");
        }
    }
}
