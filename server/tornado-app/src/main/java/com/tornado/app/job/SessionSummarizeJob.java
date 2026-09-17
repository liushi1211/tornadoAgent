package com.tornado.app.job;

import com.tornado.app.memory.LongTermMemoryService;
import com.tornado.app.memory.MemorySummarizeService;
import com.tornado.domain.chat.model.ChatSession;
import com.tornado.domain.chat.repository.ChatSessionRepository;
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

    private final ChatSessionRepository sessionRepository;
    private final LongTermMemoryService longTermMemoryService;
    private final MemorySummarizeService memorySummarizeService;

    @XxlJob("dailySweep")
    public void dailySweep() {
        List<ChatSession> sessions = sessionRepository.findArchivedBefore(LocalDateTime.now().minusDays(7));
        for (ChatSession s : sessions) {
            if (longTermMemoryService.countBySourceSession(s.getId()) == 0) {
                memorySummarizeService.summarizeSession(s.getId());
            }
        }
    }
}
