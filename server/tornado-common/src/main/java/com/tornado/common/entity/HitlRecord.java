package com.tornado.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** HITL 审批记录（thread_id 唯一） */
@Data
@TableName("hitl_record")
public class HitlRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sessionId;
    private String threadId;
    private String toolName;
    private String argsJson;
    /** APPROVED|REJECTED|EDITED */
    private String decision;
    private String editedArgsJson;
    /** PENDING|RESOLVED|EXPIRED */
    private String status;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
}
