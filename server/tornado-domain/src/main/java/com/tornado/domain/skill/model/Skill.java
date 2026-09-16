package com.tornado.domain.skill.model;

import lombok.Data;

/** 技能聚合根（纯领域对象）。enabled 语义：1 启用 0 停用（含 Agent 提议待确认） */
@Data
public class Skill {
    private Long id;
    private Long userId;
    private String name;
    private String description;
    private String contentMd;
    /** UPLOAD|MARKET|AGENT|MANUAL */
    private String source;
    private Integer enabled;
    private String version;

    public boolean isEnabled() {
        return enabled != null && enabled == 1;
    }

    public void setEnabledFlag(boolean on) {
        this.enabled = on ? 1 : 0;
    }
}
