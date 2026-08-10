package com.esmile.axis.knowledge.dto;

/** POST /api/knowledge/reindex 响应：本次重建覆盖的条目数。 */
public record ReindexResponse(int reindexed) {
}
