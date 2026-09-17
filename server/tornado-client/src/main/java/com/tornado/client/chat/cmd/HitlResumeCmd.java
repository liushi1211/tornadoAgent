package com.tornado.client.chat.cmd;

import lombok.Data;

/** HITL 决策回传续流（POST /api/chat/hitl/{threadId}/resume） */
@Data
public class HitlResumeCmd {
    /** APPROVED|REJECTED|EDITED */
    private String decision;
    private String editedArgsJson;
    private Long sessionId;
    private String modelId;
    private String streamId;
}
