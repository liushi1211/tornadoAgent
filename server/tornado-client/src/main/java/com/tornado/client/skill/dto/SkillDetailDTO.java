package com.tornado.client.skill.dto;

import java.util.List;

/** 技能详情：本体 + 资源清单 */
public record SkillDetailDTO(SkillDTO skill, List<SkillFileMetaDTO> files) {}
