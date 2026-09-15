package com.tornado.boot.chat;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * threadId → MemorySaver 复用表：保证 HITL 中断后的 resume 能在同 threadId 找回 checkpoint。
 * 简化说明：内存级，多实例部署需替换为 Redis/MySQL checkpoint saver。
 */
@Component
public class SaverRegistry {

    private final Map<String, MemorySaver> savers = new ConcurrentHashMap<>();

    public MemorySaver forThread(String threadId) {
        return savers.computeIfAbsent(threadId, k -> new MemorySaver());
    }
}
