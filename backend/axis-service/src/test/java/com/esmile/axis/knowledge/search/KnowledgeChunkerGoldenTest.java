package com.esmile.axis.knowledge.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * golden 集结构指标（knowledge-chunking lite-spec 评测项）：对 10 篇真实抓取文章跑分块，断言——
 * 坏边界率 ≤5%（旧定长滑窗基线 96%）、图片/锚点/注释零残留、零近噪声块（基线 5 块）、块 ≤500 token。
 * 确定性无网络，随 `mvn test` 默认运行，作分块契约的长期回归门。
 */
class KnowledgeChunkerGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void goldenSet_structureMetrics() throws Exception {
        String jsonl;
        try (InputStream is = getClass().getResourceAsStream("/evals/knowledge/summary-golden.jsonl")) {
            assertThat(is).as("/evals/knowledge/summary-golden.jsonl on test classpath").isNotNull();
            jsonl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        int articles = 0, chunks = 0, badBoundaries = 0, noiseChunks = 0;
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            articles++;
            JsonNode row = MAPPER.readTree(line);
            String title = row.get("title").asText();
            String content = row.get("content").asText();
            String cleaned = KnowledgeChunker.clean(content);
            String prefix = title.strip() + "\n\n";
            for (String chunk : KnowledgeChunker.chunk(title, content)) {
                String body = chunk.startsWith(prefix) ? chunk.substring(prefix.length()) : chunk;
                chunks++;
                assertThat(body).as("图片/锚点/注释零残留").doesNotContain("![", "{#", "<!--");
                assertThat(KnowledgeChunker.tokenCount(body))
                        .as("块 ≤ CHUNK_TOKENS").isLessThanOrEqualTo(KnowledgeChunker.CHUNK_TOKENS);
                if (isBadBoundary(cleaned, cleaned.indexOf(body))) {
                    badBoundaries++;
                }
                if (body.replaceAll("[#>*`|\\-\\s]", "").length() < 100) {
                    noiseChunks++;
                }
            }
        }

        int boundaries = chunks - articles;
        double badRate = boundaries == 0 ? 0 : (double) badBoundaries / boundaries;
        System.out.printf("KnowledgeChunkerGoldenTest: articles=%d chunks=%d boundaries=%d bad=%d(%.1f%%) noise=%d%n",
                articles, chunks, boundaries, badBoundaries, badRate * 100, noiseChunks);
        assertThat(badRate).as("坏边界率 ≤5%%（旧基线 96%%）").isLessThanOrEqualTo(0.05);
        assertThat(noiseChunks).as("近噪声块为零（旧基线 5 块）").isZero();
    }

    /** 边界良否：块起点前是段落空行（\n\n）或句末标点为良；容许标点与块起点间有空白。 */
    private static boolean isBadBoundary(String cleaned, int pos) {
        if (pos <= 0) {
            return false;
        }
        if (cleaned.substring(0, pos).endsWith("\n\n")) {
            return false;
        }
        int j = pos - 1;
        while (j >= 0 && Character.isWhitespace(cleaned.charAt(j))) {
            j--;
        }
        return j < 0 || "。！？.!?".indexOf(cleaned.charAt(j)) < 0;
    }
}
