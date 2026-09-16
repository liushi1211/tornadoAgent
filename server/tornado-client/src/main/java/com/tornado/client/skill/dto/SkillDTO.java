package com.tornado.client.skill.dto;

import lombok.Data;

/** 技能展示对象（enabled 用布尔，避免前端 el-switch 0/1 误触发） */
@Data
public class SkillDTO {
    private Long id;
    private String name;
    private String description;
    private String contentMd;
    private String source;
    private Boolean enabled;
    private String version;
    private String createdAt;
    private String updatedAt;
}
