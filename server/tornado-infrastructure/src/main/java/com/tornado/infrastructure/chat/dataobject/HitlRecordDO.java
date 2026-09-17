package com.tornado.infrastructure.chat.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** hitl_record 表数据对象（MyBatis-Plus），仅存在于 infrastructure */
@Data
@TableName("hitl_record")
public class HitlRecordDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sessionId;
    private String threadId;
    private String toolName;
    private String argsJson;
    private String decision;
    private String editedArgsJson;
    private String status;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
}
