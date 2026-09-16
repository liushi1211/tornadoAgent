package com.tornado.infrastructure.skill.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.client.api.PageResult;
import com.tornado.domain.skill.model.Skill;
import com.tornado.domain.skill.model.SkillFile;
import com.tornado.domain.skill.repository.SkillRepository;
import com.tornado.infrastructure.skill.dataobject.SkillDO;
import com.tornado.infrastructure.skill.dataobject.SkillFileDO;
import com.tornado.infrastructure.skill.mapper.SkillFileMapper;
import com.tornado.infrastructure.skill.mapper.SkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 技能仓储实现：DO⇄领域对象转换 + 封装 MyBatis-Plus */
@Repository
@RequiredArgsConstructor
public class SkillRepositoryImpl implements SkillRepository {

    private final SkillMapper skillMapper;
    private final SkillFileMapper skillFileMapper;

    @Override
    public boolean existsByName(Long uid, String name) {
        return skillMapper.selectCount(new LambdaQueryWrapper<SkillDO>()
                .eq(SkillDO::getUserId, uid).eq(SkillDO::getName, name).eq(SkillDO::getDeleted, 0)) > 0;
    }

    @Override
    public Skill save(Skill skill) {
        SkillDO d = new SkillDO();
        d.setUserId(skill.getUserId());
        d.setName(skill.getName());
        d.setDescription(skill.getDescription());
        d.setContentMd(skill.getContentMd());
        d.setSource(skill.getSource());
        d.setEnabled(skill.getEnabled());
        d.setVersion(skill.getVersion());
        d.setDeleted(0);
        skillMapper.insert(d);
        skill.setId(d.getId());
        return skill;
    }

    @Override
    public void saveFiles(List<SkillFile> files) {
        for (SkillFile f : files) {
            SkillFileDO d = new SkillFileDO();
            d.setSkillId(f.getSkillId());
            d.setUserId(f.getUserId());
            d.setRelPath(f.getRelPath());
            d.setFileType(f.getFileType());
            d.setContent(f.getContent());
            d.setSizeBytes(f.getSizeBytes());
            skillFileMapper.insert(d);
        }
    }

    @Override
    public Skill findById(Long id) {
        return toDomain(skillMapper.selectById(id));
    }

    @Override
    public Skill findByUserAndName(Long uid, String name) {
        SkillDO d = skillMapper.selectOne(new LambdaQueryWrapper<SkillDO>()
                .eq(SkillDO::getUserId, uid).eq(SkillDO::getName, name).eq(SkillDO::getDeleted, 0));
        return toDomain(d);
    }

    @Override
    public List<Skill> listEnabled(Long uid) {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillDO>()
                        .eq(SkillDO::getUserId, uid).eq(SkillDO::getEnabled, 1).eq(SkillDO::getDeleted, 0))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public PageResult<Skill> page(Long uid, String keyword, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) (Math.max(page, 1) - 1) * safeSize;
        LambdaQueryWrapper<SkillDO> q = buildQuery(uid, keyword);
        long total = skillMapper.selectCount(buildQuery(uid, keyword));
        q.orderByDesc(SkillDO::getUpdatedAt).last("LIMIT " + safeSize + " OFFSET " + offset);
        List<Skill> records = skillMapper.selectList(q).stream().map(this::toDomain).toList();
        return PageResult.of(total, page, safeSize, records);
    }

    private LambdaQueryWrapper<SkillDO> buildQuery(Long uid, String keyword) {
        LambdaQueryWrapper<SkillDO> q = new LambdaQueryWrapper<SkillDO>()
                .eq(SkillDO::getUserId, uid).eq(SkillDO::getDeleted, 0);
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(SkillDO::getName, keyword).or().like(SkillDO::getDescription, keyword));
        }
        return q;
    }

    @Override
    public List<SkillFile> files(Long skillId) {
        return skillFileMapper.selectList(new LambdaQueryWrapper<SkillFileDO>()
                        .eq(SkillFileDO::getSkillId, skillId).orderByAsc(SkillFileDO::getRelPath))
                .stream().map(this::toFileDomain).toList();
    }

    @Override
    public SkillFile file(Long skillId, String relPath) {
        SkillFileDO d = skillFileMapper.selectOne(new LambdaQueryWrapper<SkillFileDO>()
                .eq(SkillFileDO::getSkillId, skillId).eq(SkillFileDO::getRelPath, relPath));
        return toFileDomain(d);
    }

    @Override
    public void updateEnabled(Skill skill) {
        SkillDO d = skillMapper.selectById(skill.getId());
        if (d != null) {
            d.setEnabled(skill.getEnabled());
            skillMapper.updateById(d);
        }
    }

    @Override
    public void deleteById(Long skillId) {
        skillFileMapper.delete(new LambdaQueryWrapper<SkillFileDO>().eq(SkillFileDO::getSkillId, skillId));
        skillMapper.deleteById(skillId);
    }

    private Skill toDomain(SkillDO d) {
        if (d == null) {
            return null;
        }
        Skill s = new Skill();
        s.setId(d.getId());
        s.setUserId(d.getUserId());
        s.setName(d.getName());
        s.setDescription(d.getDescription());
        s.setContentMd(d.getContentMd());
        s.setSource(d.getSource());
        s.setEnabled(d.getEnabled());
        s.setVersion(d.getVersion());
        return s;
    }

    private SkillFile toFileDomain(SkillFileDO d) {
        if (d == null) {
            return null;
        }
        SkillFile f = new SkillFile();
        f.setSkillId(d.getSkillId());
        f.setUserId(d.getUserId());
        f.setRelPath(d.getRelPath());
        f.setFileType(d.getFileType());
        f.setContent(d.getContent());
        f.setSizeBytes(d.getSizeBytes() == null ? 0 : d.getSizeBytes());
        return f;
    }
}
