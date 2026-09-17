package com.tornado.client.chat.cmd;

import lombok.Data;

/** 发起一轮流式对话（POST /api/chat/stream） */
@Data
public class ChatCmd {
    private Long sessionId;
    private String modelId;
    private String content;
    /** 前端生成的停止用流 id，可空（后端兜底生成） */
    private String streamId;
    private Boolean useRag = true;
    private Boolean useSkills = true;
    private Boolean enableThinking = false;
}
