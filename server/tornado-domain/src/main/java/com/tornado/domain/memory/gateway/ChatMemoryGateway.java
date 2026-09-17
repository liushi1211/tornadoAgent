package com.tornado.domain.memory.gateway;

import com.tornado.domain.memory.model.ChatMessageItem;

import java.util.List;

/**
 * 短期记忆网关（domain 端口，infrastructure 用 Spring AI MessageWindowChatMemory + Redis 实现）。
 * 按 (uid, sessionId) 定位会话，写入自动套用消息窗口裁剪；应用层只读写框架无关的 ChatMessageItem。
 */
public interface ChatMemoryGateway {

    /** 追加若干条消息（触发窗口裁剪与 TTL 续期） */
    void append(Long userId, Long sessionId, List<ChatMessageItem> items);

    /** 读取当前窗口内历史（按时间序） */
    List<ChatMessageItem> load(Long userId, Long sessionId);

    /** 清空该会话的短期记忆 */
    void clear(Long userId, Long sessionId);
}
