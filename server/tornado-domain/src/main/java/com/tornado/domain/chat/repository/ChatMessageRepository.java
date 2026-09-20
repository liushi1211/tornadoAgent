package com.tornado.domain.chat.repository;

import com.tornado.domain.chat.model.ChatMessage;

import java.util.List;

/** 消息仓储接口（domain 定义，infra 实现） */
public interface ChatMessageRepository {

    ChatMessage save(ChatMessage message);

    /** 会话内消息：id 倒序、cursor 之前（不含）最多 size 条；返回倒序列表，前端回放顺序由上层处理 */
    List<ChatMessage> listBeforeCursor(Long sessionId, Long uid, Long cursor, int size);

    /** 归档摘要用：会话内指定角色的消息，按 id 升序 */
    List<ChatMessage> listBySessionRolesAsc(Long sessionId, List<String> roles);

    /** ES 回填用：跨用户按 id 升序分页取消息（offset/limit） */
    List<ChatMessage> listForIndex(long offset, int limit);
}
