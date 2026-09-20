package com.tornado.client.chat.dto;

import lombok.Data;

/** 消息全文检索命中项（来自 ES，createdAt 为索引存储的时间串）。 */
@Data
public class MessageSearchHit {
    private String id;
    private Long userId;
    private Long sessionId;
    private String role;
    private String content;
    private String thinking;
    private String toolCalls;
    private String finishReason;
    private String createdAt;
}
