package com.tornado.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long userId;
    /** user|assistant|tool|system */
    private String role;
    private String content;
    private String thinking;
    /** [{name,argsJson,resultDigest,status}] */
    private String toolCalls;
    private Long hitlId;
    private Integer promptTokens;
    private Integer completionTokens;
    /** STOP|LENGTH|ABORTED|ERROR */
    private String finishReason;
    private LocalDateTime createdAt;
}
