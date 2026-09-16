package com.tornado.domain.memory.repository;

import com.tornado.domain.memory.model.LongTermMemory;

import java.util.List;

/** 长期记忆仓储接口（domain 定义，infra 用 MyBatis-Plus 实现） */
public interface LongTermMemoryRepository {

    /** 当前用户有效记忆列表（可按 category 过滤），id 倒序，最多 limit 条 */
    List<LongTermMemory> listActive(Long uid, String category, int limit);

    /** 注入用：取该用户最近 limit 条有效记忆（id 倒序） */
    List<LongTermMemory> findRecentActive(Long uid, int limit);

    /** upsert 去重用：同 category 下的有效记忆 */
    List<LongTermMemory> findActiveByCategory(Long uid, String category);

    void save(LongTermMemory memory);

    void updateContent(LongTermMemory memory);

    void deleteByIds(Long uid, List<Long> ids);

    /** 命中计数尽力而为 +1 */
    void touchHitCount(Long id);

    /** 某来源会话是否已沉淀过记忆（兜底任务判重用） */
    long countBySourceSession(Long sessionId);
}
