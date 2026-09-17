package com.tornado.domain.chat.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话聚合根（薄域：字段与持久化一致，deleted 软删标记留在 infra DO）。
 * pinned/archived 保持 0/1 整型语义以兼容既有 API 契约；布尔转换在 app 命令层完成。
 */
@Data
public class ChatSession {

    public static final String DEFAULT_TITLE = "新会话";

    private Long id;
    private Long userId;
    private String title;
    private String modelId;
    private Integer pinned;
    private Integer archived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isDefaultTitle() {
        return DEFAULT_TITLE.equals(title);
    }

    /** 用首条用户消息生成标题（截断 20 字）；仅在默认标题时生效，返回是否更新 */
    public boolean applyTitleFromContent(String content) {
        if (!isDefaultTitle() || content == null || content.isBlank()) {
            return false;
        }
        this.title = content.length() > 20 ? content.substring(0, 20) : content;
        return true;
    }
}
