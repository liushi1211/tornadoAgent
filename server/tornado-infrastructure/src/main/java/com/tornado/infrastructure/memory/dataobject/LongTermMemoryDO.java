package com.tornado.infrastructure.memory.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** long_term_memory 表数据对象（MyBatis-Plus），仅存在于 infrastructure */
@Data
@TableName("long_term_memory")
public class LongTermMemoryDO {
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
