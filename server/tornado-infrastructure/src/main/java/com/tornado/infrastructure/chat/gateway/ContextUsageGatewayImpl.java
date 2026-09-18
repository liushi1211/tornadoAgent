package com.tornado.infrastructure.chat.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.domain.chat.gateway.ContextUsageGateway;
import com.tornado.domain.chat.model.ContextUsage;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 上下文用量快照的 Redis 实现：key ctx:usage:{uid}:{sid}，TTL 7d */
@Component
@RequiredArgsConstructor
public class ContextUsageGatewayImpl implements ContextUsageGateway {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String PREFIX = "ctx:usage:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private String key(Long userId, Long sessionId) {
        return PREFIX + userId + ":" + sessionId;
    }

    @SneakyThrows
    @Override
    public void save(Long userId, Long sessionId, ContextUsage usage) {
        redis.opsForValue().set(key(userId, sessionId), objectMapper.writeValueAsString(usage), TTL);
    }

    @SneakyThrows
    @Override
    public ContextUsage load(Long userId, Long sessionId) {
        String json = redis.opsForValue().get(key(userId, sessionId));
        return json == null || json.isBlank() ? null : objectMapper.readValue(json, ContextUsage.class);
    }
}
