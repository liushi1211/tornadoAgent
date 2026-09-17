package com.tornado.domain.chat.model;

import lombok.Data;

import java.time.LocalDateTime;

/** HITL 审批记录聚合根（thread_id 唯一）。状态机：PENDING→RESOLVED/EXPIRED */
@Data
public class HitlRecord {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_RESOLVED = "RESOLVED";
    public static final String ST_EXPIRED = "EXPIRED";

    private Long id;
    private Long userId;
    private Long sessionId;
    private String threadId;
    private String toolName;
    private String argsJson;
    /** APPROVED|REJECTED|EDITED */
    private String decision;
    private String editedArgsJson;
    private String status;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;

    public void resolve(String decision, String editedArgsJson) {
        this.status = ST_RESOLVED;
        this.decision = decision;
        this.editedArgsJson = editedArgsJson;
        this.decidedAt = LocalDateTime.now();
    }
}
