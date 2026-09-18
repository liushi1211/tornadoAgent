package com.tornado.domain.chat.gateway;

import com.tornado.domain.chat.model.ContextUsage;

/** 上下文用量缓存端口（domain 定义，infrastructure 用 Redis 实现），供前端展示占用与压缩后刷新 */
public interface ContextUsageGateway {

    void save(Long userId, Long sessionId, ContextUsage usage);

    /** 无记录返回 null */
    ContextUsage load(Long userId, Long sessionId);
}
