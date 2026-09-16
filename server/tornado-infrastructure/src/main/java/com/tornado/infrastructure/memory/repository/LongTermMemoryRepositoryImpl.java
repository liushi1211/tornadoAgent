package com.tornado.infrastructure.memory.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tornado.domain.memory.model.LongTermMemory;
import com.tornado.domain.memory.repository.LongTermMemoryRepository;
import com.tornado.infrastructure.memory.dataobject.LongTermMemoryDO;
import com.tornado.infrastructure.memory.mapper.LongTermMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 长期记忆仓储实现：DO⇄领域对象转换 + 封装 MyBatis-Plus（手动 LIMIT/OFFSET，无分页插件） */
@Repository
@RequiredArgsConstructor
public class LongTermMemoryRepositoryImpl implements LongTermMemoryRepository {

    private final LongTermMemoryMapper memoryMapper;

    @Override
    public List<LongTermMemory> listActive(Long uid, String category, int limit) {
        LambdaQueryWrapper<LongTermMemoryDO> q = new LambdaQueryWrapper<LongTermMemoryDO>()
                .eq(LongTermMemoryDO::getUserId, uid)
                .eq(LongTermMemoryDO::getStatus, LongTermMemory.ST_ACTIVE)
                .orderByDesc(LongTermMemoryDO::getId)
                .last("LIMIT " + limit);
        if (category != null && !category.isBlank()) {
            q.eq(LongTermMemoryDO::getCategory, category);
        }
        return memoryMapper.selectList(q).stream().map(this::toDomain).toList();
    }

    @Override
    public List<LongTermMemory> findRecentActive(Long uid, int limit) {
        return memoryMapper.selectList(new LambdaQueryWrapper<LongTermMemoryDO>()
                        .eq(LongTermMemoryDO::getUserId, uid)
                        .eq(LongTermMemoryDO::getStatus, LongTermMemory.ST_ACTIVE)
                        .orderByDesc(LongTermMemoryDO::getId)
                        .last("LIMIT " + limit))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<LongTermMemory> findActiveByCategory(Long uid, String category) {
        return memoryMapper.selectList(new LambdaQueryWrapper<LongTermMemoryDO>()
                        .eq(LongTermMemoryDO::getUserId, uid)
                        .eq(LongTermMemoryDO::getStatus, LongTermMemory.ST_ACTIVE)
                        .eq(LongTermMemoryDO::getCategory, category))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void save(LongTermMemory m) {
        LongTermMemoryDO d = toDO(m);
        memoryMapper.insert(d);
        m.setId(d.getId());
    }

    @Override
    public void updateContent(LongTermMemory m) {
        LongTermMemoryDO d = memoryMapper.selectById(m.getId());
        if (d != null) {
            d.setContent(m.getContent());
            memoryMapper.updateById(d);
        }
    }

    @Override
    public void deleteByIds(Long uid, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        memoryMapper.delete(new LambdaQueryWrapper<LongTermMemoryDO>()
                .eq(LongTermMemoryDO::getUserId, uid)
                .in(LongTermMemoryDO::getId, ids));
    }

    @Override
    public void touchHitCount(Long id) {
        memoryMapper.update(null, new LambdaUpdateWrapper<LongTermMemoryDO>()
                .eq(LongTermMemoryDO::getId, id)
                .setSql("hit_count = hit_count + 1"));
    }

    @Override
    public long countBySourceSession(Long sessionId) {
        return memoryMapper.selectCount(new LambdaQueryWrapper<LongTermMemoryDO>()
                .eq(LongTermMemoryDO::getSourceSessionId, sessionId));
    }

    private LongTermMemoryDO toDO(LongTermMemory m) {
        LongTermMemoryDO d = new LongTermMemoryDO();
        d.setId(m.getId());
        d.setUserId(m.getUserId());
        d.setCategory(m.getCategory());
        d.setContent(m.getContent());
        d.setSourceSessionId(m.getSourceSessionId());
        d.setHitCount(m.getHitCount());
        d.setStatus(m.getStatus());
        d.setCreatedAt(m.getCreatedAt());
        d.setUpdatedAt(m.getUpdatedAt());
        return d;
    }

    private LongTermMemory toDomain(LongTermMemoryDO d) {
        if (d == null) {
            return null;
        }
        LongTermMemory m = new LongTermMemory();
        m.setId(d.getId());
        m.setUserId(d.getUserId());
        m.setCategory(d.getCategory());
        m.setContent(d.getContent());
        m.setSourceSessionId(d.getSourceSessionId());
        m.setHitCount(d.getHitCount());
        m.setStatus(d.getStatus());
        m.setCreatedAt(d.getCreatedAt());
        m.setUpdatedAt(d.getUpdatedAt());
        return m;
    }
}
