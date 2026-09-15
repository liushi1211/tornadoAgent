package com.tornado.boot.chat;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReactAgent checkpoint saver 注册表。
 * 主路径：SAA graph-core 的 RedisSaver（Redisson），执行状态与 HITL 断点持久化到 Redis，
 * 跨请求、跨重启、跨实例均可按 threadId 恢复续跑。RedisSaver 线程安全且内部按
 * threadName 区分会话，全部会话共用一个实例即可。
 * 降级：Redis 不可用时回退进程内 MemorySaver（仅单实例可用，启动时告警）。
 */
@Slf4j
@Component
public class SaverRegistry {

    /** 非 null 表示 RedisSaver 可用 */
    private final BaseCheckpointSaver redisSaver;
    private final RedissonClient redisson;
    /** 降级路径才使用的 threadId → MemorySaver 表 */
    private final Map<String, BaseCheckpointSaver> fallback = new ConcurrentHashMap<>();

    public SaverRegistry(@Value("${spring.data.redis.host:localhost}") String host,
                         @Value("${spring.data.redis.port:6379}") int port,
                         @Value("${spring.data.redis.password:}") String password) {
        BaseCheckpointSaver saver = null;
        RedissonClient client = null;
        try {
            Config cfg = new Config();
            cfg.useSingleServer()
                    .setAddress("redis://" + host + ":" + port)
                    .setConnectionMinimumIdleSize(2)
                    .setConnectionPoolSize(8)
                    .setConnectTimeout(3000)
                    .setTimeout(3000);
            if (password != null && !password.isBlank()) {
                cfg.useSingleServer().setPassword(password);
            }
            client = Redisson.create(cfg);
            saver = RedisSaver.builder()
                    .redisson(client)
                    // 与 ReactAgent 的 OverAllState 匹配的状态序列化器（支持 Message 等复杂对象）
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new))
                    .build();
            log.info("Checkpoint saver 已启用 RedisSaver: redis://{}:{}", host, port);
        } catch (Exception e) {
            log.warn("RedisSaver 初始化失败，降级为进程内 MemorySaver（多实例/HITL 跨实例恢复不可用）: {}", e.getMessage());
            if (client != null) {
                try {
                    client.shutdown();
                } catch (Exception ignored) {
                }
                client = null;
            }
            saver = null;
        }
        this.redisSaver = saver;
        this.redisson = client;
    }

    /**
     * 返回该 threadId 使用的 saver。RedisSaver 模式下所有线程共用单实例（按 threadName 隔离），
     * 降级模式下每 threadId 一个 MemorySaver，与旧行为一致。
     */
    public BaseCheckpointSaver forThread(String threadId) {
        if (redisSaver != null) {
            return redisSaver;
        }
        return fallback.computeIfAbsent(threadId, k -> new MemorySaver());
    }

    /**
     * 轮次结束后释放该 thread 的 checkpoint（RedisSaver 标记释放 + 清理降级表项），
     * 防止每轮新 threadId 在 Redis/内存中无限累积。
     */
    public void releaseThread(String threadId) throws Exception {
        fallback.remove(threadId);
        if (redisSaver != null) {
            redisSaver.release(com.alibaba.cloud.ai.graph.RunnableConfig.builder().threadId(threadId).build());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (redisson != null) {
            try {
                redisson.shutdown();
            } catch (Exception ignored) {
            }
        }
    }
}
