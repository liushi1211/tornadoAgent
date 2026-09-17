package com.tornado.domain.chat.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 聊天消息实体（薄域）：字段与原持久化实体一致，toolCalls 仍以 JSON 字符串承载（列表契约保持） */
@Data
public class ChatMessage {

    private Long id;
    private Long sessionId;
    private Long userId;
    /** user|assistant|tool|system */
    private String role;
    private String content;
    private String thinking;
    /** [{name,argsJson,resultDigest,status}] 的 JSON 串 */
    private String toolCalls;
    private Long hitlId;
    private Integer promptTokens;
    private Integer completionTokens;
    /** STOP|LENGTH|ABORTED|ERROR */
    private String finishReason;
    private LocalDateTime createdAt;
}
