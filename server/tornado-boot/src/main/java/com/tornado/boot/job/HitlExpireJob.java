package com.tornado.boot.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tornado.common.entity.HitlRecord;
import com.tornado.common.mapper.HitlRecordMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 每 10min 将超过 30min 未决策的 PENDING HITL 置 EXPIRED（暂存进程内，过期即不可 resume） */
@Slf4j
@Component
@RequiredArgsConstructor
public class HitlExpireJob {

    private final HitlRecordMapper hitlRecordMapper;

    @XxlJob("hitlExpireJob")
    public void expire() {
        int n = hitlRecordMapper.update(null, new LambdaUpdateWrapper<HitlRecord>()
                .eq(HitlRecord::getStatus, "PENDING")
                .lt(HitlRecord::getCreatedAt, LocalDateTime.now().minusMinutes(30))
                .set(HitlRecord::getStatus, "EXPIRED"));
        if (n > 0) {
            log.info("HITL 过期处理 {} 条", n);
        }
    }
}
