package com.tornado.domain.rag.repository;

import com.tornado.client.api.PageResult;
import com.tornado.domain.rag.model.RagDocument;

/** RAG 文档仓储接口（domain 定义，infra 用 MyBatis-Plus 实现） */
public interface RagDocumentRepository {

    /** 落库并回填自增 id */
    RagDocument save(RagDocument doc);

    /** 按 id 全量更新（状态机推进、错误信息、chunk/token 计数） */
    void update(RagDocument doc);

    RagDocument findById(Long id);

    /** 按用户 + 状态分页（status 为空表示不过滤），id 倒序 */
    PageResult<RagDocument> page(Long uid, String status, int page, int size);

    /** 软删元数据（@TableLogic deleted） */
    void deleteById(Long id);
}
