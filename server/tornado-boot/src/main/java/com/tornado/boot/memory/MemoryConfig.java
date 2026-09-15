package com.tornado.boot.memory;

import lombok.Data;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 短期记忆：MessageWindowChatMemory(窗口 20) + Redis 仓库 */
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
