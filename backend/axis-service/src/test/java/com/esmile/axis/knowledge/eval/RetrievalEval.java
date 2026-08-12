package com.esmile.axis.knowledge.eval;

import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import com.esmile.axis.knowledge.search.HybridKnowledgeSearchService;
import com.esmile.axis.knowledge.search.KnowledgeIndexService;
import com.esmile.axis.knowledge.search.KnowledgeSearchHit;
import com.esmile.axis.knowledge.search.KeywordKnowledgeSearchService;
import com.esmile.axis.knowledge.search.VectorKnowledgeIndexService;
import com.esmile.axis.knowledge.search.VectorKnowledgeSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * Evaluation entry for hybrid retrieval (docs/feature/knowledge-hybrid-search).
 *
 * <p>Indexes the golden set's synthetic items into pgvector (real embedding), then runs
 * each golden query through two modes — vector-only lane vs hybrid (vector + keyword RRF
 * fusion, rerank off) — and compares hit@5. The keyword lane is backed by a mocked
 * repository implementing the same case-insensitive LIKE semantics as the JPQL query,
 * so no knowledge_item rows are written; vectors are deleted on teardown.
 *
 * <p>Gated like {@link KnowledgeVectorSearchEval}: skipped unless a real
 * {@code AI_API_KEY} is set and the local DB has pgvector enabled.
 */
class RetrievalEval {

    private static final double HYBRID_HIT_RATE_MIN = 0.80;
    private static final int TOP_K = 10;
    private static final double SIMILARITY_THRESHOLD = 0.2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static VectorStore vectorStore;
    private static List<String> itemIds;
    private static KeywordKnowledgeSearchService keywordLane;
    private static VectorKnowledgeSearchService vectorLane;
    private static HybridKnowledgeSearchService hybrid;

    record EvalItem(String id, String title, String content) {
    }

    record EvalQuery(String q, String expect, String note) {
    }

    record Golden(List<EvalItem> items, List<EvalQuery> queries) {
    }

    @BeforeAll
    static void setUp() throws Exception {
        Golden golden;
        try (InputStream is = RetrievalEval.class.getResourceAsStream("/evals/knowledge/retrieval-golden.json")) {
            assertThat(is).as("retrieval-golden.json should be on test classpath").isNotNull();
            golden = MAPPER.readValue(is, Golden.class);
        }
        assertThat(golden.queries()).hasSizeGreaterThanOrEqualTo(20);

        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"demo".equals(apiKey),
                "Skipping RetrievalEval: set AI_API_KEY env var to a real key");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/axis"),
                System.getenv().getOrDefault("DB_USERNAME", "alan"),
                System.getenv().getOrDefault("DB_PASSWORD", "password"));
        Assumptions.assumeTrue(pgvectorEnabled(dataSource),
                "Skipping RetrievalEval: pgvector extension not enabled in local DB");

        // 镜像 AiConfigService 的解析链：激活的 EMBEDDING 档案（解密）优先，env 兜底
        String[] embedding = resolveEmbeddingConfig(dataSource, apiKey);
        OpenAiEmbeddingOptions opts = OpenAiEmbeddingOptions.builder()
                .apiKey(embedding[0])
                .baseUrl(embedding[1])
                .model(embedding[2])
                .dimensions(1536) // 对齐 AiConfigService / vector_store 表（智谱 embedding-3 等可变维度模型必须显式指定）
                .build();
        PgVectorStore store = PgVectorStore.builder(new JdbcTemplate(dataSource),
                        OpenAiEmbeddingModel.builder().options(opts).build())
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet();
        vectorStore = store;

        // Mock 仓储：findById 供索引，search 复刻 JPQL 的不区分大小写 LIKE 语义（不写 knowledge_item 表）
        Map<String, KnowledgeItem> byId = new LinkedHashMap<>();
        for (EvalItem ei : golden.items()) {
            KnowledgeItem item = KnowledgeItem.builder()
                    .type(KnowledgeType.NOTE).title(ei.title()).content(ei.content()).build();
            item.setId(ei.id());
            byId.put(ei.id(), item);
        }
        KnowledgeItemRepository itemRepository = Mockito.mock(KnowledgeItemRepository.class);
        Mockito.when(itemRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byId.get(inv.getArgument(0))));
        Mockito.when(itemRepository.search(isNull(), isNull(), isNull(), any(String.class)))
                .thenAnswer(inv -> {
                    String q = ((String) inv.getArgument(3)).toLowerCase();
                    return byId.values().stream()
                            .filter(i -> i.getTitle().toLowerCase().contains(q)
                                    || i.getContent().toLowerCase().contains(q))
                            .toList();
                });
        Mockito.when(itemRepository.searchByToken(anyString()))
                .thenAnswer(inv -> {
                    String t = ((String) inv.getArgument(0)).toLowerCase();
                    return byId.values().stream()
                            .filter(i -> i.getTitle().toLowerCase().contains(t)
                                    || i.getContent().toLowerCase().contains(t))
                            .toList();
                });

        itemIds = List.copyOf(byId.keySet());
        VectorKnowledgeIndexService indexService = new VectorKnowledgeIndexService(() -> vectorStore, itemRepository);
        for (String id : itemIds) {
            indexService.indexItem(id);
        }

        keywordLane = new KeywordKnowledgeSearchService(itemRepository);
        vectorLane = new VectorKnowledgeSearchService(() -> vectorStore, keywordLane, TOP_K, SIMILARITY_THRESHOLD);
        hybrid = new HybridKnowledgeSearchService(vectorLane, keywordLane, (query, candidates) -> candidates, TOP_K);
    }

    @AfterAll
    static void tearDown() {
        if (vectorStore != null && itemIds != null) {
            for (String itemId : itemIds) {
                try {
                    vectorStore.delete(new FilterExpressionBuilder()
                            .eq(KnowledgeIndexService.META_ITEM_ID, itemId).build());
                } catch (Exception ignored) {
                    // 清理失败不影响评测结果
                }
            }
        }
    }

    @Test
    void hybridOutperformsVectorOnlyOnGoldenQueries() throws Exception {
        Golden golden;
        try (InputStream is = RetrievalEval.class.getResourceAsStream("/evals/knowledge/retrieval-golden.json")) {
            golden = MAPPER.readValue(is, Golden.class);
        }

        int[] cutoffs = {1, 3, 5};
        Map<Integer, Integer> vectorHits = new LinkedHashMap<>();
        Map<Integer, Integer> hybridHits = new LinkedHashMap<>();
        for (int k : cutoffs) {
            vectorHits.put(k, 0);
            hybridHits.put(k, 0);
        }

        for (EvalQuery query : golden.queries()) {
            List<String> vectorIds = vectorLane.vectorHits(query.q()).stream()
                    .map(KnowledgeSearchHit::itemId).toList();
            List<String> hybridIds = hybrid.search(query.q()).stream()
                    .map(KnowledgeSearchHit::itemId).toList();
            for (int k : cutoffs) {
                if (vectorIds.stream().limit(k).anyMatch(query.expect()::equals)) {
                    vectorHits.merge(k, 1, Integer::sum);
                }
                if (hybridIds.stream().limit(k).anyMatch(query.expect()::equals)) {
                    hybridHits.merge(k, 1, Integer::sum);
                }
            }
            System.out.printf("RetrievalEval q=%s expect=%s vector@5=%s hybrid@5=%s%n",
                    query.q(), query.expect(),
                    vectorIds.stream().limit(5).anyMatch(query.expect()::equals) ? "HIT" : "MISS",
                    hybridIds.stream().limit(5).anyMatch(query.expect()::equals) ? "HIT" : "MISS");
        }

        int total = golden.queries().size();
        for (int k : cutoffs) {
            System.out.printf("RetrievalEval: queries=%d hit@%d vector-only=%.2f hybrid=%.2f%n",
                    total, k, (double) vectorHits.get(k) / total, (double) hybridHits.get(k) / total);
        }

        double vectorRate5 = (double) vectorHits.get(5) / total;
        double hybridRate5 = (double) hybridHits.get(5) / total;
        assertThat(hybridRate5).as("hybrid hit@5 ≥ %.0f%%", HYBRID_HIT_RATE_MIN * 100)
                .isGreaterThanOrEqualTo(HYBRID_HIT_RATE_MIN);
        assertThat(hybridRate5).as("hybrid hit@5 不低于 vector-only 基线")
                .isGreaterThanOrEqualTo(vectorRate5);
    }

    /** @return [apiKey, baseUrl, model]：激活的 EMBEDDING 档案（解密）优先，env 兜底——镜像 AiConfigService。 */
    private static String[] resolveEmbeddingConfig(DriverManagerDataSource ds, String envApiKey) {
        try (Connection conn = ds.getConnection();
             ResultSet rs = conn.createStatement().executeQuery(
                     "SELECT endpoint, model, api_key FROM ai_config_profile WHERE type='EMBEDDING' AND is_active=true LIMIT 1")) {
            if (rs.next()) {
                TextEncryptor encryptor = Encryptors.delux(
                        System.getenv().getOrDefault("AXIS_ENCRYPTION_PASSWORD", "dev-only-do-not-use-in-prod"),
                        System.getenv().getOrDefault("AXIS_ENCRYPTION_SALT", "0123456789abcdef"));
                return new String[]{encryptor.decrypt(rs.getString("api_key")),
                        rs.getString("endpoint"), rs.getString("model")};
            }
        } catch (Exception e) {
            System.out.println("RetrievalEval: embedding 档案读取/解密失败，回退 env — " + e);
        }
        return new String[]{envApiKey,
                System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com"),
                System.getenv().getOrDefault("AI_EMBEDDING_MODEL", "text-embedding-3-small")};
    }

    private static boolean pgvectorEnabled(DriverManagerDataSource ds) {
        try (Connection conn = ds.getConnection();
             ResultSet rs = conn.createStatement()
                     .executeQuery("SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
