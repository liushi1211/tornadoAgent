package com.tornado.client.skill.cmd;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 手工录入技能命令 */
@Data
public class SkillInstallCmd {
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String contentMd;
    private String source = "MANUAL";
}
