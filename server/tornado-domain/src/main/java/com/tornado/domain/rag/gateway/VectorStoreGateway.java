package com.tornado.domain.rag.gateway;

import com.tornado.domain.rag.model.VectorChunk;
import com.tornado.domain.rag.model.VectorHit;

import java.util.List;

/**
 * 向量库网关（domain 定义，infrastructure 用 Spring AI RedisVectorStore 实现）。
 * 封装 embedding 写入、按用户命名空间的 ANN 检索、以及“删除整篇文档全部切片”的 id 约定。
 */
public interface VectorStoreGateway {

    /** 批量写入切片（含 embedding） */
    void add(List<VectorChunk> chunks) throws Exception;

    /** 删除某文档的全部切片（chunk 数 chunkCount，id 约定由实现内部维护） */
    void deleteByDocument(Long docId, int chunkCount);

    /** 租户内 ANN 检索：强制 user_id 过滤 */
    List<VectorHit> search(String query, Long userId, int topK);
}
