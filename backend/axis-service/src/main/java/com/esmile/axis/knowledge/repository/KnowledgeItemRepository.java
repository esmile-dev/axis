package com.esmile.axis.knowledge.repository;

import com.esmile.axis.knowledge.KnowledgeStatus;
import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeItemRepository extends JpaRepository<KnowledgeItem, String> {

    List<KnowledgeItem> findAllByOrderByCreatedAtDesc();

    /** 全量重建向量索引用：只取 id，避免把全部 content 拉进内存。 */
    @Query("SELECT k.id FROM KnowledgeItem k")
    List<String> findAllIds();

    /** 列表筛选：四个参数均可空（null = 不筛选），q 匹配 title 与 content（大小写不敏感）。CAST 为防 PostgreSQL 把 null 参数推断成 bytea。 */
    @Query("""
            SELECT k FROM KnowledgeItem k
            WHERE (:type IS NULL OR k.type = :type)
              AND (:status IS NULL OR k.status = :status)
              AND (:tag IS NULL OR :tag MEMBER OF k.tags)
              AND (:q IS NULL OR LOWER(k.title) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%'))
                   OR LOWER(k.content) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%')))
            ORDER BY k.createdAt DESC
            """)
    List<KnowledgeItem> search(@Param("type") KnowledgeType type,
                               @Param("status") KnowledgeStatus status,
                               @Param("tag") String tag,
                               @Param("q") String q);
}
