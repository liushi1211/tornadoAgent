package com.tornado.infrastructure.skill.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** skill 表数据对象（MyBatis-Plus），仅存在于 infrastructure */
@Data
@TableName("skill")
public class SkillDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String description;
    private String contentMd;
    private String source;
    private Integer enabled;
    private String version;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
