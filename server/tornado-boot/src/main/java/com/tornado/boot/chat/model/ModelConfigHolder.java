package com.tornado.boot.chat.model;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生效中的模型清单缓存：本地 application.yml 兜底，Nacos 拉取成功后整体替换；
 * version 变化时 ChatModelFactory 失效缓存重建（进行中的流按已取实例继续，不受影响）。
 */
@Component
public class ModelConfigHolder {

    private final AtomicReference<List<ChatModelProperties.ModelDef>> models = new AtomicReference<>(List.of());
    private final AtomicReference<String> defaultModel = new AtomicReference<>("qwen-plus");
    private final AtomicLong version = new AtomicLong();

    public void replace(ChatModelProperties chatModelProperties) {
        this.models.set(List.copyOf(chatModelProperties.getModels()));
        this.defaultModel.set(chatModelProperties.getDefaultModel());
        this.version.incrementAndGet();
    }

    public long version() {
        return version.get();
    }

    public List<ChatModelProperties.ModelDef> list() {
        return models.get();
    }

    public String defaultModel() {
        return defaultModel.get();
    }

    public ChatModelProperties.ModelDef find(String id) {
        return models.get().stream().filter(m -> m.getId().equals(id)).findFirst().orElse(null);
    }

    /** 找不到指定 id 时回落到 default */
    public ChatModelProperties.ModelDef findOrDefault(String id) {
        ChatModelProperties.ModelDef m = id == null || id.isBlank() ? null : find(id);
        return m != null ? m : find(defaultModel.get());
    }
}
