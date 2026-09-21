package com.tornado.domain.rag.gateway;

import com.tornado.domain.rag.model.RagChunkHit;
import com.tornado.domain.rag.model.RagChunkIndex;

import java.util.List;

/**
 * RAG 切片 ES 索引网关（domain 端口，infrastructure 实现）：
 * canal 消费者用 bulkUpsert/bulkDelete 灌数，检索用 bm25 关键词召回。
 */
public interface RagChunkIndexGateway {

    void ensureIndex();

    void bulkUpsert(List<RagChunkIndex> docs);

    void bulkDelete(List<String> ids);

    /** 关键词召回：按 userId 过滤，返回 topK BM25 命中 */
    List<RagChunkHit> bm25(Long userId, String query, int topK);
}
