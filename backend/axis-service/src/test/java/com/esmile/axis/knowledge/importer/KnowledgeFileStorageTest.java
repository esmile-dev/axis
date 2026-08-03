package com.esmile.axis.knowledge.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KnowledgeFileStorage}: files land under the knowledge/
 * subdirectory of the upload dir with a UUID + extension name, and the returned
 * path is the relative form persisted on KnowledgeItem.filePath.
 */
class KnowledgeFileStorageTest {

    @TempDir
    Path uploadDir;

    @Test
    void store_writesUnderKnowledgeSubdirAndReturnsRelativePath() throws IOException {
        KnowledgeFileStorage storage = new KnowledgeFileStorage(uploadDir.toString());

        String relativePath = storage.store("内容".getBytes(), ".md");

        assertThat(relativePath).matches("knowledge/[0-9a-f-]{36}\\.md");
        Path stored = uploadDir.resolve(relativePath);
        assertThat(stored).exists();
        assertThat(Files.readString(stored)).isEqualTo("内容");
    }

    @Test
    void store_keepsGivenExtension() {
        KnowledgeFileStorage storage = new KnowledgeFileStorage(uploadDir.toString());

        assertThat(storage.store(new byte[]{1}, ".pdf")).endsWith(".pdf");
    }
}
