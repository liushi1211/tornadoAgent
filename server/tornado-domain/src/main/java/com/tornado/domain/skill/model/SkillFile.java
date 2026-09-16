package com.tornado.domain.skill.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 技能包内资源文件（值对象） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillFile {
    private Long skillId;
    private Long userId;
    private String relPath;
    /** SCRIPT|DOC|ASSET */
    private String fileType;
    private String content;
    private int sizeBytes;
}
