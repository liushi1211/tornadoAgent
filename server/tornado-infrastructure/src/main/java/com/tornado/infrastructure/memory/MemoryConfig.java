package com.tornado.infrastructure.memory;

import lombok.Data;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 短期记忆装配：MessageWindowChatMemory(窗口 window) + Redis 仓库；longtermModel 供归档摘要选便宜模型 */
@Data
@Configuration
@ConfigurationProperties(prefix = "saa.memory")
public class MemoryConfig {

    private int window = 20;
    private int injectTopk = 8;
    private String longtermModel = "qwen-turbo";

    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(window)
                .build();
    }
}
