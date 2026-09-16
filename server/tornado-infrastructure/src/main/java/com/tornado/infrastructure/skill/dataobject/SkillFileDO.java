package com.tornado.infrastructure.skill.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** skill_file 表数据对象 */
@Data
@TableName("skill_file")
public class SkillFileDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private Long userId;
    private String relPath;
    private String fileType;
    private String content;
    private Integer sizeBytes;
    private LocalDateTime createdAt;
}
