package com.tornado.client.skill.dto;

/** 技能资源文件元信息（不含内容） */
public record SkillFileMetaDTO(String relPath, String fileType, Integer sizeBytes) {}
