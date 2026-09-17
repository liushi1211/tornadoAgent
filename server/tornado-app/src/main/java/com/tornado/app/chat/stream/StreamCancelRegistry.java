package com.tornado.app.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 停止链路：本机 ConcurrentHashMap<streamId, Disposable> 直停；
 * 本机没有则 PUBLISH chat:stop（消息体 {streamId}），各节点订阅后由持有者 dispose（幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamCancelRegistry {

    public static final String STOP_CHANNEL = "chat:stop";

    private final Map<String, Disposable> streams = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    /** 由 doOnSubscribe 调用：登记停止动作（置中断标志 + 发射取消信号，见 ChatStreamCmdExe） */
    public void register(String streamId, Disposable disposable) {
        ensureSubscriber();
        log.info("登记可停流 streamId={}", streamId);
        streams.put(streamId, disposable);
    }

    public void remove(String streamId) {
        streams.remove(streamId);
    }

    /** 幂等停止：本机命中直接 dispose，否则广播给持有节点 */
    public void stop(String streamId) {
        Disposable d = streams.remove(streamId);
        if (d != null) {
            log.info("本机命中，dispose streamId={}", streamId);
            if (!d.isDisposed()) {
                d.dispose();
            }
            return;
        }
        log.info("本机未命中，广播 chat:stop streamId={}（当前登记数={}）", streamId, streams.size());
        try {
            redis.convertAndSend(STOP_CHANNEL, streamId);
        } catch (Exception e) {
            log.warn("停止广播失败 streamId={}: {}", streamId, e.getMessage());
        }
    }

    private void ensureSubscriber() {
        if (subscribed.compareAndSet(false, true)) {
            listenerContainer.addMessageListener(this::onMessage, new ChannelTopic(STOP_CHANNEL));
        }
    }

    private void onMessage(Message message, byte[] pattern) {
        String streamId = new String(message.getBody(), StandardCharsets.UTF_8).replace("\"", "");
        Disposable d = streams.remove(streamId);
        if (d != null && !d.isDisposed()) {
            log.info("收到 chat:stop 广播，dispose streamId={}", streamId);
            d.dispose();
        }
    }

    @PreDestroy
    public void shutdown() {
        streams.values().forEach(d -> {
            if (!d.isDisposed()) {
                d.dispose();
            }
        });
    }
}
