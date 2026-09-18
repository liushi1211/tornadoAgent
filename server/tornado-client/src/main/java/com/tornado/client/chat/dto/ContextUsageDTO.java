package com.tornado.client.chat.dto;

import lombok.Data;

/** 会话上下文占用（估算）：已用 token、模型窗口、百分比 */
@Data
public class ContextUsageDTO {
    private String modelId;
    /** 估算已占用 token */
    private int usedTokens;
    /** 模型最大输入上下文 token */
    private int contextWindow;
    /** 占用百分比（0-100，四舍五入） */
    private int percent;
}
