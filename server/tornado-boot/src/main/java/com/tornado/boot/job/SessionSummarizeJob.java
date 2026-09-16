package com.tornado.boot.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.app.memory.LongTermMemoryService;
import com.tornado.boot.memory.MemorySummarizeService;
import com.tornado.common.entity.ChatSession;
import com.tornado.common.mapper.ChatSessionMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSummarizeJob {

    /** 每日 03:00：归档超过 7 天且从未沉淀过记忆的会话补做摘要（简化实现） */

    private final ChatSessionMapper sessionMapper;
    private final LongTermMemoryService longTermMemoryService;
    private final MemorySummarizeService memorySummarizeService;

    @XxlJob("dailySweep")
    public void dailySweep() {
        List<ChatSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getArchived, 1)
                .lt(ChatSession::getUpdatedAt, LocalDateTime.now().minusDays(7)));
        for (ChatSession s : sessions) {
            if (longTermMemoryService.countBySourceSession(s.getId()) == 0) {
                memorySummarizeService.summarizeSession(s.getId());
            }
        }
    }
}
