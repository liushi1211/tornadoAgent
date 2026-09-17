package com.tornado.infrastructure.chat.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** chat_session 表数据对象（MyBatis-Plus），仅存在于 infrastructure */
@Data
@TableName("chat_session")
public class ChatSessionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String modelId;
    private Integer pinned;
    private Integer archived;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
