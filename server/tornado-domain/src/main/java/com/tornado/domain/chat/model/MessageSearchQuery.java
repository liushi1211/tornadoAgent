package com.tornado.domain.chat.model;

/** 消息全文检索查询（domain 值对象，框架无关）。keyword 走 IK 分词 match；其余为过滤条件，null 表示不过滤。 */
public record MessageSearchQuery(
        Long userId,
        String keyword,
        Long sessionId,
        String role,
        String from,
        String to,
        int page,
        int size) {
}
