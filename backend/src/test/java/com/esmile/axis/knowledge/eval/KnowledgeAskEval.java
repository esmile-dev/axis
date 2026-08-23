package com.esmile.axis.knowledge.eval;

import com.esmile.axis.llm.AiConfigService;
import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.llm.LlmCallLogger;
import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.chat.KnowledgeAskService;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import com.esmile.axis.knowledge.search.HybridKnowledgeSearchService;
import com.esmile.axis.knowledge.search.KnowledgeIndexService;
import com.esmile.axis.knowledge.search.KeywordKnowledgeSearchService;
import com.esmile.axis.knowledge.search.VectorKnowledgeIndexService;
import com.esmile.axis.knowledge.search.VectorKnowledgeSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
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
 * Evaluation entry for cross-item RAG ask (docs/feature/knowledge-ask-rag, FR-006).
 *
 * <p>Corpus: the 10 synthetic items from retrieval-golden.json, indexed into pgvector
 * with the real embedding model (DB EMBEDDING profile, decrypted — mirrors
 * {@code AiConfigService}). The ask chain runs the real hybrid search + a real ChatClient
 * (env AI_*); the repository is mocked with the same LIKE semantics as the JPQL query.
 *
 * <p>Per-question deterministic assertions: answer non-blank + contains ≥1 expected
 * keyword + the expected item appears in sources and its {@code [n]} marker appears in
 * the answer. Gate: pass rate ≥ 80%. Skipped unless a real {@code AI_API_KEY} is set
 * and the local DB has pgvector enabled.
 */
class KnowledgeAskEval {

    private static final double PASS_RATE_MIN = 0.80;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static VectorStore vectorStore;
    private static List<String> itemIds;
    private static KnowledgeAskService askService;

    record EvalItem(String id, String title, String content) {
    }

    record EvalQuery(String q, String expect, String note) {
    }

    record RetrievalGolden(List<EvalItem> items, List<EvalQuery> queries) {
    }

    record AskQuery(String q, String expectItem, List<String> keywords) {
    }

    record AskGolden(List<AskQuery> questions) {
    }

    @BeforeAll
    static void setUp() throws Exception {
        RetrievalGolden corpus;
        try (InputStream is = KnowledgeAskEval.class.getResourceAsStream("/evals/knowledge/retrieval-golden.json")) {
            assertThat(is).as("retrieval-golden.json should be on test classpath").isNotNull();
            corpus = MAPPER.readValue(is, RetrievalGolden.class);
        }

        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"demo".equals(apiKey),
                "Skipping KnowledgeAskEval: set AI_API_KEY env var to a real key");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/axis"),
                System.getenv().getOrDefault("DB_USERNAME", "alan"),
                System.getenv().getOrDefault("DB_PASSWORD", "password"));
        Assumptions.assumeTrue(pgvectorEnabled(dataSource),
                "Skipping KnowledgeAskEval: pgvector extension not enabled in local DB");

        // embedding：镜像 AiConfigService——激活的 EMBEDDING 档案（解密）优先，env 兜底
        String[] embedding = resolveEmbeddingConfig(dataSource, apiKey);
        OpenAiEmbeddingOptions embeddingOpts = OpenAiEmbeddingOptions.builder()
                .apiKey(embedding[0])
                .baseUrl(embedding[1])
                .model(embedding[2])
                .dimensions(1536)
                .build();
        PgVectorStore store = PgVectorStore.builder(new JdbcTemplate(dataSource),
                        OpenAiEmbeddingModel.builder().options(embeddingOpts).build())
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet();
        vectorStore = store;

        // chat：env 的 CHAT 配置（与 SummarizationEval 同款）
        OpenAiChatOptions chatOpts = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com"))
                .model(System.getenv().getOrDefault("AI_MODEL", "gpt-4o-mini"))
                .build();
        ChatClient chatClient = ChatClient.create(OpenAiChatModel.builder().options(chatOpts).build());
        AiConfigService aiConfigService = Mockito.mock(AiConfigService.class);
        Mockito.when(aiConfigService.get()).thenReturn(chatClient);

        // Mock 仓储：复刻 JPQL LIKE 语义，不写 knowledge_item 表
        Map<String, KnowledgeItem> byId = new LinkedHashMap<>();
        for (EvalItem ei : corpus.items()) {
            KnowledgeItem item = KnowledgeItem.builder()
                    .type(KnowledgeType.NOTE).title(ei.title()).content(ei.content()).build();
            item.setId(ei.id());
            byId.put(ei.id(), item);
        }
        KnowledgeItemRepository itemRepository = Mockito.mock(KnowledgeItemRepository.class);
        Mockito.when(itemRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byId.get(inv.getArgument(0))));
        Mockito.when(itemRepository.findAllById(any()))
                .thenAnswer(inv -> {
                    List<KnowledgeItem> result = new java.util.ArrayList<>();
                    for (String id : (Iterable<String>) inv.getArgument(0)) {
                        KnowledgeItem item = byId.get(id);
                        if (item != null) {
                            result.add(item);
                        }
                    }
                    return result;
                });
        Mockito.when(itemRepository.search(isNull(), isNull(), isNull(), any(String.class)))
                .thenAnswer(inv -> {
                    String q = ((String) inv.getArgument(3)).toLowerCase();
                    return byId.values().stream().filter(i -> likeMatch(i, q)).toList();
                });
        Mockito.when(itemRepository.searchByToken(anyString()))
                .thenAnswer(inv -> {
                    String t = ((String) inv.getArgument(0)).toLowerCase();
                    return byId.values().stream().filter(i -> likeMatch(i, t)).toList();
                });

        itemIds = List.copyOf(byId.keySet());
        VectorKnowledgeIndexService indexService = new VectorKnowledgeIndexService(() -> vectorStore, itemRepository);
        for (String id : itemIds) {
            indexService.indexItem(id);
        }

        KeywordKnowledgeSearchService keywordLane = new KeywordKnowledgeSearchService(itemRepository);
        VectorKnowledgeSearchService vectorLane = new VectorKnowledgeSearchService(() -> vectorStore, keywordLane, 10, 0.2);
        HybridKnowledgeSearchService hybrid = new HybridKnowledgeSearchService(
                vectorLane, keywordLane, (query, candidates) -> candidates, 10);
        askService = new KnowledgeAskService(hybrid, itemRepository,
                new ChatGateway(aiConfigService, Mockito.mock(ChatMemory.class), Mockito.mock(LlmCallLogger.class)));
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
    void askGoldenQuestions() throws Exception {
        AskGolden golden;
        try (InputStream is = KnowledgeAskEval.class.getResourceAsStream("/evals/knowledge/ask-golden.json")) {
            assertThat(is).as("ask-golden.json should be on test classpath").isNotNull();
            golden = MAPPER.readValue(is, AskGolden.class);
        }
        assertThat(golden.questions()).hasSizeGreaterThanOrEqualTo(10);

        int passed = 0;
        for (AskQuery question : golden.questions()) {
            KnowledgeAskService.AskResult result = askService.ask(question.q());
            String answer = String.join("", result.answer().collectList().block());

            int citationIndex = -1;
            for (KnowledgeAskService.AskSource s : result.sources()) {
                if (s.itemId().equals(question.expectItem())) {
                    citationIndex = s.n();
                    break;
                }
            }
            boolean cited = citationIndex > 0 && answer.contains("[" + citationIndex + "]");
            boolean keywordHit = question.keywords().stream()
                    .anyMatch(k -> answer.toLowerCase().contains(k.toLowerCase()));
            boolean ok = !answer.isBlank() && cited && keywordHit;
            if (ok) {
                passed++;
            }
            System.out.printf("KnowledgeAskEval q=%s expect=%s cited@%d=%s keyword=%s => %s%n",
                    question.q(), question.expectItem(), citationIndex, cited, keywordHit, ok ? "PASS" : "FAIL");
            System.out.printf("  answer: %s%n", answer.length() > 200 ? answer.substring(0, 200) + "…" : answer);
        }

        double rate = (double) passed / golden.questions().size();
        System.out.printf("KnowledgeAskEval: total=%d pass=%.2f%n", golden.questions().size(), rate);
        assertThat(rate).as("ask pass rate ≥ %.0f%%", PASS_RATE_MIN * 100)
                .isGreaterThanOrEqualTo(PASS_RATE_MIN);
    }

    private static boolean likeMatch(KnowledgeItem item, String lowerQuery) {
        return item.getTitle().toLowerCase().contains(lowerQuery)
                || item.getContent().toLowerCase().contains(lowerQuery);
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
            System.out.println("KnowledgeAskEval: embedding 档案读取/解密失败，回退 env — " + e);
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
