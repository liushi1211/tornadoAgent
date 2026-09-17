package com.tornado.client.chat.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 会话展示对象（字段与原实体序列化保持一致，仅去掉持久化专用的 deleted） */
@Data
public class ChatSessionDTO {
    private Long id;
    private Long userId;
    private String title;
    private String modelId;
    /** 0/1，前端按真值判断 */
    private Integer pinned;
    /** 0/1 */
    private Integer archived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
