package com.tornado.infrastructure.chat.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** chat_message 表数据对象（MyBatis-Plus），仅存在于 infrastructure */
@Data
@TableName("chat_message")
public class ChatMessageDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long userId;
    private String role;
    private String content;
    private String thinking;
    private String toolCalls;
    private Long hitlId;
    private Integer promptTokens;
    private Integer completionTokens;
    private String finishReason;
    private LocalDateTime createdAt;
}
