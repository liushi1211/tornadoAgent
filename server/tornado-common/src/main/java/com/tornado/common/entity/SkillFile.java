package com.tornado.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 技能包内资源文件（脚本/文档/资产），随 .skill(zip) 安装写入；文本内容存 content */
@Data
@TableName("skill_file")
public class SkillFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private Long userId;
    /** 包内相对路径，如 scripts/count.js */
    private String relPath;
    /** SCRIPT|DOC|ASSET */
    private String fileType;
    private String content;
    private Integer sizeBytes;
    private LocalDateTime createdAt;
}
