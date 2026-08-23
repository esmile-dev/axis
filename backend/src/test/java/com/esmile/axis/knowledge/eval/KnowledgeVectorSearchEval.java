package com.esmile.axis.knowledge.eval;

import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import com.esmile.axis.knowledge.search.KnowledgeIndexService;
import com.esmile.axis.knowledge.search.KnowledgeSearchHit;
import com.esmile.axis.knowledge.search.VectorKnowledgeIndexService;
import com.esmile.axis.knowledge.search.VectorKnowledgeSearchService;
import com.esmile.axis.knowledge.search.KeywordKnowledgeSearchService;
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

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluation entry for FR-010 positive path (semantic search over pgvector).
 *
 * <p>Real chain: {@link VectorKnowledgeIndexService} (chunk + embed + write) →
 * {@link VectorKnowledgeSearchService} (similaritySearch) against the local DB, with a
 * tiny 2-doc sample to bound embedding cost. Assertion: a semantically related query
 * that shares no literal title words ranks the target item first.
 *
 * <p>Gated like {@code KnowledgeQaEval}: skipped unless (a) the local DB has the
 * vector extension enabled and (b) {@code AI_API_KEY} is a real key. Connection
 * settings come from {@code DATABASE_URL}/{@code DB_USERNAME}/{@code DB_PASSWORD}
 * (defaults match application.yml); embedding model from {@code AI_EMBEDDING_MODEL}
 * (default text-embedding-3-small). Embedding options fix {@code dimensions=1536} to match
 * {@code AiConfigService} / the vector_store table (variable-dimension models like 智谱
 * embedding-3 default to 3072 otherwise and the insert fails).
 */
class KnowledgeVectorSearchEval {

    private static final String ITEM_JVM = "eval-vector-jvm";
    private static final String ITEM_RECIPE = "eval-vector-recipe";
    private static final String ITEM_HNSW = "eval-vector-hnsw";

    /** 图文混排长文：图片/锚点噪声 + 细节埋在文中部（knowledge-chunking 评测增强样例）。 */
    private static final String HNSW_CONTENT = """
            ![](https://cdn.example.com/banner.png)
            ## 为什么需要向量索引 {#why}
            暴力扫描在百万级向量上延迟无法接受，近似最近邻索引用精度换速度。
            ![](https://cdn.example.com/arch.png)
            ## HNSW 的参数 {#params}
            HNSW 索引中，m 参数决定图里每个节点的最大连接数。增大 m 提升召回率，
            但每个节点的内存开销近似线性增长，生产上通常取 16 到 64 之间权衡。
            ![](https://cdn.example.com/bench.png)
            ## 选型建议 {#choice}
            小规模语料直接用 pgvector 复用业务库即可，百万级以上再考虑专用向量库。
            """;

    private static DriverManagerDataSource dataSource;
    private static VectorStore vectorStore;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"demo".equals(apiKey),
                "Skipping KnowledgeVectorSearchEval: set AI_API_KEY env var to a real key");

        dataSource = new DriverManagerDataSource(
                System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/axis"),
                System.getenv().getOrDefault("DB_USERNAME", "alan"),
                System.getenv().getOrDefault("DB_PASSWORD", "password"));
        Assumptions.assumeTrue(pgvectorEnabled(dataSource),
                "Skipping KnowledgeVectorSearchEval: pgvector extension not enabled in local DB");

        String embeddingModel = System.getenv().getOrDefault("AI_EMBEDDING_MODEL", "text-embedding-3-small");
        OpenAiEmbeddingOptions opts = OpenAiEmbeddingOptions.builder()
                .apiKey(apiKey)
                .baseUrl(System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com"))
                .model(embeddingModel)
                .dimensions(1536) // 对齐 AiConfigService.EMBEDDING_DIMENSIONS / vector_store 表
                .build();
        PgVectorStore store = PgVectorStore.builder(new JdbcTemplate(dataSource),
                        OpenAiEmbeddingModel.builder().options(opts).build())
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet();
        vectorStore = store;
    }

    @AfterAll
    static void tearDown() {
        if (vectorStore != null) {
            for (String itemId : List.of(ITEM_JVM, ITEM_RECIPE, ITEM_HNSW)) {
                vectorStore.delete(new FilterExpressionBuilder()
                        .eq(KnowledgeIndexService.META_ITEM_ID, itemId).build());
            }
        }
    }

    @Test
    void semanticQueryRanksTargetItemFirst() {
        KnowledgeItemRepository itemRepository = Mockito.mock(KnowledgeItemRepository.class);
        stubItem(itemRepository, ITEM_JVM, "JVM 性能调优手记",
                "线上服务频繁 Full GC 导致接口毛刺。通过堆 Dump 分析定位到缓存无上限，" +
                        "引入弱引用与淘汰策略后停顿时间从秒级降到百毫秒。");
        stubItem(itemRepository, ITEM_RECIPE, "番茄意面食谱",
                "番茄去皮切块，橄榄油爆香蒜末，小火熬煮二十分钟，加盐与罗勒调味，拌入煮好的意面即可。");

        VectorKnowledgeIndexService indexService = new VectorKnowledgeIndexService(() -> vectorStore, itemRepository);
        indexService.indexItem(ITEM_JVM);
        indexService.indexItem(ITEM_RECIPE);

        VectorKnowledgeSearchService searchService = new VectorKnowledgeSearchService(() -> vectorStore,
                new KeywordKnowledgeSearchService(itemRepository), 5, 0.0);
        // 语义相近但不含标题/正文原词（"垃圾回收" 未在标题出现，"Java" 全文未出现）
        List<KnowledgeSearchHit> hits = searchService.search("Java 垃圾回收停顿怎么排查");

        System.out.printf("KnowledgeVectorSearchEval hits=%s%n", hits);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).itemId()).as("语义最相关条目应排第一").isEqualTo(ITEM_JVM);
    }

    @Test
    void detailQueryInImageHeavyDoc_ranksTargetFirst() {
        KnowledgeItemRepository itemRepository = Mockito.mock(KnowledgeItemRepository.class);
        stubItem(itemRepository, ITEM_HNSW, "向量数据库学习笔记", HNSW_CONTENT);
        stubItem(itemRepository, ITEM_RECIPE, "番茄意面食谱",
                "番茄去皮切块，橄榄油爆香蒜末，小火熬煮二十分钟，加盐与罗勒调味，拌入煮好的意面即可。");

        VectorKnowledgeIndexService indexService = new VectorKnowledgeIndexService(() -> vectorStore, itemRepository);
        indexService.indexItem(ITEM_HNSW);
        indexService.indexItem(ITEM_RECIPE);

        VectorKnowledgeSearchService searchService = new VectorKnowledgeSearchService(() -> vectorStore,
                new KeywordKnowledgeSearchService(itemRepository), 5, 0.0);
        // 针对文中部细节的语义查询：不含标题原词，"m 参数/连接数" 细节只在第二节中部出现
        List<KnowledgeSearchHit> hits = searchService.search("图索引节点连接数调大对内存的影响");

        System.out.printf("KnowledgeVectorSearchEval(image-heavy) hits=%s%n", hits);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).itemId()).as("图文混排文档的文中部细节应命中该条目").isEqualTo(ITEM_HNSW);
    }

    private static void stubItem(KnowledgeItemRepository repo, String id, String title, String content) {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.NOTE).title(title).content(content).build();
        item.setId(id);
        Mockito.when(repo.findById(id)).thenReturn(Optional.of(item));
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
