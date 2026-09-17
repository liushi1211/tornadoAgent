package com.tornado.infrastructure.memory;

import com.tornado.domain.memory.gateway.ChatMemoryGateway;
import com.tornado.domain.memory.model.ChatMessageItem;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 短期记忆网关实现：委托 Spring AI ChatMemory（MessageWindowChatMemory，写入即窗口裁剪），
 * 会话键 "{uid}:{sid}"；ChatMessageItem⇄Message 的框架转换只在此发生。
 */
@Component
@RequiredArgsConstructor
public class RedisChatMemoryGatewayImpl implements ChatMemoryGateway {

    private final ChatMemory chatMemory;

    @Override
    public void append(Long userId, Long sessionId, List<ChatMessageItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        chatMemory.add(convId(userId, sessionId), items.stream().map(RedisChatMemoryGatewayImpl::toMessage).toList());
    }

    @Override
    public List<ChatMessageItem> load(Long userId, Long sessionId) {
        return chatMemory.get(convId(userId, sessionId)).stream()
                .map(m -> new ChatMessageItem(m.getMessageType().getValue(), m.getText()))
                .toList();
    }

    @Override
    public void clear(Long userId, Long sessionId) {
        chatMemory.clear(convId(userId, sessionId));
    }

    private String convId(Long userId, Long sessionId) {
        return RedisChatMemoryRepository.convId(userId, sessionId);
    }

    private static Message toMessage(ChatMessageItem item) {
        return switch (item.role() == null ? "" : item.role()) {
            case ChatMessageItem.ROLE_ASSISTANT -> new AssistantMessage(item.text());
            case ChatMessageItem.ROLE_SYSTEM -> new SystemMessage(item.text());
            default -> new UserMessage(item.text());
        };
    }
}
