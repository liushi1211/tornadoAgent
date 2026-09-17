package com.tornado.infrastructure.chat.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tornado.domain.chat.model.HitlRecord;
import com.tornado.domain.chat.repository.HitlRecordRepository;
import com.tornado.infrastructure.chat.dataobject.HitlRecordDO;
import com.tornado.infrastructure.chat.mapper.HitlRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/** HITL 审批记录仓储实现：DO⇄领域对象转换 + 封装 MyBatis-Plus */
@Repository
@RequiredArgsConstructor
public class HitlRecordRepositoryImpl implements HitlRecordRepository {

    private final HitlRecordMapper hitlRecordMapper;

    @Override
    public HitlRecord findByThreadId(String threadId) {
        HitlRecordDO d = hitlRecordMapper.selectOne(new LambdaQueryWrapper<HitlRecordDO>()
                .eq(HitlRecordDO::getThreadId, threadId));
        return toDomain(d);
    }

    @Override
    public void save(HitlRecord r) {
        HitlRecordDO d = toDO(r);
        hitlRecordMapper.insert(d);
        r.setId(d.getId());
        r.setCreatedAt(d.getCreatedAt());
    }

    @Override
    public void update(HitlRecord r) {
        hitlRecordMapper.updateById(toDO(r));
    }

    @Override
    public int expirePendingBefore(LocalDateTime cutoff) {
        return hitlRecordMapper.update(null, new LambdaUpdateWrapper<HitlRecordDO>()
                .eq(HitlRecordDO::getStatus, HitlRecord.ST_PENDING)
                .lt(HitlRecordDO::getCreatedAt, cutoff)
                .set(HitlRecordDO::getStatus, HitlRecord.ST_EXPIRED));
    }

    private HitlRecordDO toDO(HitlRecord r) {
        HitlRecordDO d = new HitlRecordDO();
        d.setId(r.getId());
        d.setUserId(r.getUserId());
        d.setSessionId(r.getSessionId());
        d.setThreadId(r.getThreadId());
        d.setToolName(r.getToolName());
        d.setArgsJson(r.getArgsJson());
        d.setDecision(r.getDecision());
        d.setEditedArgsJson(r.getEditedArgsJson());
        d.setStatus(r.getStatus());
        d.setDecidedAt(r.getDecidedAt());
        d.setCreatedAt(r.getCreatedAt());
        return d;
    }

    private HitlRecord toDomain(HitlRecordDO d) {
        if (d == null) {
            return null;
        }
        HitlRecord r = new HitlRecord();
        r.setId(d.getId());
        r.setUserId(d.getUserId());
        r.setSessionId(d.getSessionId());
        r.setThreadId(d.getThreadId());
        r.setToolName(d.getToolName());
        r.setArgsJson(d.getArgsJson());
        r.setDecision(d.getDecision());
        r.setEditedArgsJson(d.getEditedArgsJson());
        r.setStatus(d.getStatus());
        r.setDecidedAt(d.getDecidedAt());
        r.setCreatedAt(d.getCreatedAt());
        return r;
    }
}
