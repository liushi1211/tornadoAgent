package com.tornado.domain.chat.repository;

import com.tornado.domain.chat.model.ChatSession;

import java.time.LocalDateTime;
import java.util.List;

/** 会话仓储接口（domain 定义，infra 实现；所有查询按 userId 隔离） */
public interface ChatSessionRepository {

    ChatSession save(ChatSession session);

    void update(ChatSession session);

    ChatSession findById(Long id);

    /** 置顶降序、更新时间降序 */
    List<ChatSession> listByUser(Long uid);

    void deleteById(Long id);

    /** 兜底任务：归档且更新时间早于 cutoff 的会话 */
    List<ChatSession> findArchivedBefore(LocalDateTime cutoff);
}
