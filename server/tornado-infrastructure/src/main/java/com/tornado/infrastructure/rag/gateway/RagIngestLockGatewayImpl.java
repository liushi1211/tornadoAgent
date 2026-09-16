package com.tornado.infrastructure.rag.gateway;

import com.tornado.domain.rag.gateway.RagIngestLockGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 入库多实例互斥锁实现（Redis SETNX + TTL） */
@Component
@RequiredArgsConstructor
public class RagIngestLockGatewayImpl implements RagIngestLockGateway {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String KEY_PREFIX = "rag:ingest:lock:";

    private final StringRedisTemplate redis;

    @Override
    public boolean tryLock(Long docId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY_PREFIX + docId, "1", TTL));
    }

    @Override
    public void unlock(Long docId) {
        redis.delete(KEY_PREFIX + docId);
    }
}
