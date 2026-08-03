package com.esmile.axis.knowledge.importer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Self-contained storage for the knowledge import pipeline (20MB cap and the
 * extension allowlist are enforced upstream by {@link ImportedFileParser}).
 * Files live under {@code ${app.upload.dir}/knowledge/} as {@code UUID + extension};
 * the returned path is relative to the upload dir and lands on {@code KnowledgeItem.filePath}.
 */
@Component
public class KnowledgeFileStorage {

    private final Path knowledgeDir;

    public KnowledgeFileStorage(@Value("${app.upload.dir}") String uploadDir) {
        this.knowledgeDir = Paths.get(uploadDir).resolve("knowledge");
    }

    public String store(byte[] bytes, String extension) {
        String filename = UUID.randomUUID() + extension;
        try {
            Files.createDirectories(knowledgeDir);
            Files.write(knowledgeDir.resolve(filename), bytes);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORE_FAILED: 文件写入失败");
        }
        return "knowledge/" + filename;
    }
}
