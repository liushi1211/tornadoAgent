package com.tornado.infrastructure.memory.config;

import com.tornado.domain.memory.gateway.MemorySettingsGateway;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * saa.memory.* 长期记忆相关参数（injectTopk），实现 domain MemorySettingsGateway。
 * 短期记忆窗口 window / 摘要模型 longtermModel 目前仍由 boot 侧 MemoryConfig 承载（M5 随 chat 一起归位）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "saa.memory")
public class MemoryProperties implements MemorySettingsGateway {
    private int injectTopk = 8;

    @Override
    public int injectTopk() {
        return injectTopk;
    }
}
