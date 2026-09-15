package com.tornado.boot.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 短期记忆 Redis 实现：key u:{uid}:chat_mem:{sid}（LIST，TTL 7d，每次写续期）。
 * conversationId 约定为 "{uid}:{sid}"。
 */
@Component
@RequiredArgsConstructor
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private String key(String conversationId) {
        String[] parts = conversationId.split(":", 2);
        return "u:" + parts[0] + ":chat_mem:" + (parts.length > 1 ? parts[1] : "0");
    }

    @Override
    public List<String> findConversationIds() {
        return List.of();
    }

    @SneakyThrows
    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> raw = redis.opsForList().range(key(conversationId), 0, -1);
        List<Message> out = new ArrayList<>();
        if (raw != null) {
            for (String j : raw) {
                Map<?, ?> m = objectMapper.readValue(j, Map.class);
                String type = String.valueOf(m.get("type"));
                String text = m.get("text") == null ? "" : m.get("text").toString();
                if (MessageType.USER.getValue().equals(type)) {
                    out.add(new UserMessage(text));
                } else if (MessageType.ASSISTANT.getValue().equals(type)) {
                    out.add(new AssistantMessage(text));
                } else if (MessageType.SYSTEM.getValue().equals(type)) {
                    out.add(new SystemMessage(text));
                }
            }
        }
        return out;
    }

    @SneakyThrows
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String k = key(conversationId);
        redis.delete(k);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<String> jsons = new ArrayList<>();
        for (Message m : messages) {
            jsons.add(objectMapper.writeValueAsString(
                    Map.of("type", m.getMessageType().getValue(), "text", m.getText() == null ? "" : m.getText())));
        }
        redis.opsForList().rightPushAll(k, jsons);
        redis.expire(k, TTL);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(key(conversationId));
    }

    /** 当前登录用户 + 会话 id 拼 conversationId */
    public static String convId(Long uid, Long sessionId) {
        return uid + ":" + sessionId;
    }
}
