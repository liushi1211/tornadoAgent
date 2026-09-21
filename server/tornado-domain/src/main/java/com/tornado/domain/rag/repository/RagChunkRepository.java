package com.tornado.domain.rag.repository;

import com.tornado.domain.rag.model.RagChunk;

import java.util.List;

/** RAG 切片仓储（domain 定义，infra 用 MyBatis-Plus 实现）。 */
public interface RagChunkRepository {

    void saveBatch(List<RagChunk> chunks);

    /** 删除文档时清理其全部切片（canal 会捕获 DELETE → 移除 ES 文档） */
    void deleteByDoc(Long docId);
}
