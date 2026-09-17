package com.tornado.app.job;

import com.tornado.domain.chat.repository.HitlRecordRepository;
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

    private final HitlRecordRepository hitlRecordRepository;

    @XxlJob("hitlExpireJob")
    public void expire() {
        int n = hitlRecordRepository.expirePendingBefore(LocalDateTime.now().minusMinutes(30));
        if (n > 0) {
            log.info("HITL 过期处理 {} 条", n);
        }
    }
}
