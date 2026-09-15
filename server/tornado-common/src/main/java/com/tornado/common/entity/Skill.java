package com.tornado.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 技能（删除采用物理删除，规避 uk_user_name(user_id,name,deleted) 软删撞唯一键） */
@Data
@TableName("skill")
public class Skill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String description;
    private String contentMd;
    /** UPLOAD|MARKET|AGENT|MANUAL */
    private String source;
    private Integer enabled;
    private String version;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
