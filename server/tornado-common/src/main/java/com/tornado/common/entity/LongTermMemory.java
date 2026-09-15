package com.tornado.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 长期记忆（preference|fact|summary，跨会话共享） */
@Data
@TableName("long_term_memory")
public class LongTermMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String category;
    private String content;
    private Long sourceSessionId;
    private Integer hitCount;
    /** 1 有效 2 已合并淘汰 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
