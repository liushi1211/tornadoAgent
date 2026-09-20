package com.tornado.domain.chat.gateway;

import com.tornado.domain.chat.model.ChatMessageIndex;

import java.util.List;

/**
 * 消息检索索引网关（domain 端口，infrastructure 用 Elasticsearch 实现）。
 * 供 canal 消费者把 MySQL binlog 变更灌入 ES；查询侧后续复用同一索引。
 */
public interface ChatMessageIndexGateway {

    /** 批量写入/覆盖（按 ChatMessageIndex.id 幂等 upsert） */
    void bulkUpsert(List<ChatMessageIndex> docs);

    /** 按主键批量删除 */
    void bulkDelete(List<String> ids);

    /** 确保索引存在（含 IK 分词 mapping），幂等 */
    void ensureIndex();
}
