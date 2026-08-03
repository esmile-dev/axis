package com.esmile.axis.knowledge.repository;

import com.esmile.axis.knowledge.KnowledgeStatus;
import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link KnowledgeItemRepository#search} against the real local
 * PostgreSQL (axis 库) — the JPQL semantics (null-tolerant params, tag MEMBER OF,
 * case-insensitive LIKE, CAST guard for null q) cannot be covered by mocked unit tests.
 * Each test runs in a transaction that rolls back, so the axis 库 is not polluted.
 * Assertions use contains/doesNotContain on fixture ids to stay robust against
 * pre-existing dev data.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class KnowledgeItemRepositorySearchTest {

    @Autowired
    private KnowledgeItemRepository repository;

    private String idA;
    private String idB;
    private String idC;

    @BeforeEach
    void setUp() {
        idA = repository.save(KnowledgeItem.builder()
                .type(KnowledgeType.ARTICLE).status(KnowledgeStatus.UNREAD)
                .title("IT-T002 向量检索实践").content("聊聊 embedding 与 itkwalpha 检索")
                .tags(new HashSet<>(Set.of("it-tag-a", "it-tag-common")))
                .build()).getId();
        idB = repository.save(KnowledgeItem.builder()
                .type(KnowledgeType.BOOK).status(KnowledgeStatus.READING)
                .title("IT-T002 领域驱动设计").content("聚合与值对象 itkwbeta")
                .tags(new HashSet<>(Set.of("it-tag-b", "it-tag-common")))
                .build()).getId();
        idC = repository.save(KnowledgeItem.builder()
                .type(KnowledgeType.ARTICLE).status(KnowledgeStatus.DONE)
                .title("IT-T002 第三篇笔记").content("plain note")
                .build()).getId();
    }

    @Test
    void search_byTypeOnly() {
        List<String> ids = ids(repository.search(KnowledgeType.ARTICLE, null, null, null));

        assertThat(ids).contains(idA, idC).doesNotContain(idB);
    }

    @Test
    void search_byStatusOnly() {
        List<String> ids = ids(repository.search(null, KnowledgeStatus.READING, null, null));

        assertThat(ids).contains(idB).doesNotContain(idA, idC);
    }

    @Test
    void search_byTagOnly_memberOfMatches() {
        List<String> ids = ids(repository.search(null, null, "it-tag-common", null));

        assertThat(ids).contains(idA, idB).doesNotContain(idC);
    }

    @Test
    void search_byKeywordOnly() {
        List<String> ids = ids(repository.search(null, null, null, "itkwbeta"));

        assertThat(ids).contains(idB).doesNotContain(idA, idC);
    }

    @Test
    void search_allFiltersCombined() {
        List<String> ids = ids(repository.search(
                KnowledgeType.ARTICLE, KnowledgeStatus.UNREAD, "it-tag-a", "itkwalpha"));

        assertThat(ids).contains(idA).doesNotContain(idB, idC);
    }

    @Test
    void search_keywordCaseInsensitive_uppercaseHitsLowercaseContent() {
        List<String> ids = ids(repository.search(null, null, null, "ITKWALPHA"));

        assertThat(ids).contains(idA).doesNotContain(idB, idC);
    }

    @Test
    void search_nullKeyword_castBranchDoesNotThrow() {
        List<String> ids = ids(repository.search(KnowledgeType.BOOK, null, null, null));

        assertThat(ids).contains(idB).doesNotContain(idA, idC);
    }

    @Test
    void search_noMatch_returnsEmpty() {
        List<KnowledgeItem> result = repository.search(null, null, null, "itkw-absent-zzz");

        assertThat(result).isEmpty();
    }

    private static List<String> ids(List<KnowledgeItem> items) {
        return items.stream().map(KnowledgeItem::getId).toList();
    }
}
