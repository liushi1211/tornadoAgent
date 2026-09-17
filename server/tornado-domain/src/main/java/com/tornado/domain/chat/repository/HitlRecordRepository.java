package com.tornado.domain.chat.repository;

import com.tornado.domain.chat.model.HitlRecord;

import java.time.LocalDateTime;

/** HITL 审批记录仓储接口（domain 定义，infra 实现） */
public interface HitlRecordRepository {

    HitlRecord findByThreadId(String threadId);

    void save(HitlRecord record);

    void update(HitlRecord record);

    /** 兜底任务：把早于 cutoff 仍 PENDING 的记录置 EXPIRED，返回影响行数 */
    int expirePendingBefore(LocalDateTime cutoff);
}
