package com.tornado.domain.chat.model;

/** 写入 ES 的消息索引文档（值对象，框架无关）。id 为字符串主键（=chat_message.id）。 */
public record ChatMessageIndex(
        String id,
        Long userId,
        Long sessionId,
        String role,
        String content,
        String thinking,
        String toolCalls,
        String finishReason,
        String createdAt) {
}
