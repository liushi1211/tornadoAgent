package com.tornado.domain.skill.repository;

import com.tornado.client.api.PageResult;
import com.tornado.domain.skill.model.Skill;
import com.tornado.domain.skill.model.SkillFile;

import java.util.List;

/** 技能仓储接口（domain 定义，infra 实现） */
public interface SkillRepository {
    boolean existsByName(Long uid, String name);

    Skill save(Skill skill);

    void saveFiles(List<SkillFile> files);

    Skill findById(Long id);

    Skill findByUserAndName(Long uid, String name);

    List<Skill> listEnabled(Long uid);

    PageResult<Skill> page(Long uid, String keyword, int page, int size);

    List<SkillFile> files(Long skillId);

    SkillFile file(Long skillId, String relPath);

    void updateEnabled(Skill skill);

    /** 物理删除技能 + 其资源文件（规避软删撞唯一键） */
    void deleteById(Long skillId);
}
