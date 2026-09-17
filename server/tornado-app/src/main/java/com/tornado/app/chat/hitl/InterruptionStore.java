package com.tornado.app.chat.hitl;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HITL 中断元数据暂存：ConcurrentHashMap<threadId, InterruptionMetadata>。
 * 简化说明：进程内暂存（TTL 未实现，过期由 @Scheduled 扫 hitl_record PENDING>30min 置 EXPIRED 兜底），
 * 多实例部署需迁移到 Redis u:{uid}:hitl:{threadId}（TTL 30min）。
 */
@Slf4j
@Component
public class InterruptionStore {

    public record Stored(Long uid, Long sessionId, InterruptionMetadata metadata, long storedAt) {}

    private final Map<String, Stored> store = new ConcurrentHashMap<>();

    public void put(String threadId, Long uid, Long sessionId, InterruptionMetadata metadata) {
        store.put(threadId, new Stored(uid, sessionId, metadata, System.currentTimeMillis()));
    }

    public Stored take(String threadId, Long uid) {
        Stored s = store.get(threadId);
        if (s == null || !s.uid().equals(uid)) {
            return null;
        }
        store.remove(threadId);
        return s;
    }

    public Map<String, Stored> snapshot() {
        return Map.copyOf(store);
    }
}
