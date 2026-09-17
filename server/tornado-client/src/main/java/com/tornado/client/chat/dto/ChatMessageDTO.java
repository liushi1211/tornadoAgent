package com.tornado.client.chat.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 消息展示对象（toolCalls 仍为 JSON 字符串，与迁移前实体一致，避免破坏历史回放契约） */
@Data
public class ChatMessageDTO {
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
