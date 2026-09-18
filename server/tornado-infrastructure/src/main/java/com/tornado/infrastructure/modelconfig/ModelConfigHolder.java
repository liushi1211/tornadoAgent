package com.tornado.infrastructure.modelconfig;

import com.tornado.domain.modelconfig.ModelCatalog;
import com.tornado.domain.modelconfig.model.ModelDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生效中的模型清单缓存（实现 domain ModelCatalog）：Nacos 拉取成功后整体替换为领域值对象列表；
 * version 变化时 ChatModelFactory 失效缓存重建（进行中的流按已取实例继续，不受影响）。
 */
@Component
public class ModelConfigHolder implements ModelCatalog {

    private final AtomicReference<List<ModelDefinition>> models = new AtomicReference<>(List.of());
    private final AtomicReference<String> defaultModel = new AtomicReference<>("qwen-plus");
    private final AtomicLong version = new AtomicLong();

    public void replace(ChatModelProperties props) {
        this.models.set(props.getModels().stream().map(ModelConfigHolder::toDefinition).toList());
        this.defaultModel.set(props.getDefaultModel());
        this.version.incrementAndGet();
    }

    @Override
    public long version() {
        return version.get();
    }

    @Override
    public List<ModelDefinition> list() {
        return models.get();
    }

    @Override
    public ModelDefinition find(String id) {
        return models.get().stream().filter(m -> m.getId().equals(id)).findFirst().orElse(null);
    }

    @Override
    public String defaultModel() {
        return defaultModel.get();
    }

    /** 找不到指定 id 时回落到 default */
    @Override
    public ModelDefinition findOrDefault(String id) {
        ModelDefinition m = id == null || id.isBlank() ? null : find(id);
        return m != null ? m : find(defaultModel.get());
    }

    private static ModelDefinition toDefinition(ChatModelProperties.ModelDef d) {
        ModelDefinition m = new ModelDefinition();
        m.setId(d.getId());
        m.setProvider(d.getProvider());
        m.setApiKey(d.getApiKey());
        m.setBaseUrl(d.getBaseUrl());
        m.setDisplayName(d.getDisplayName());
        m.setSupportsThinking(d.isSupportsThinking());
        m.setTemperature(d.getTemperature());
        m.setMaxTokens(d.getMaxTokens());
        m.setContextWindow(parseWindow(d.getContextWindow()));
        return m;
    }

    /** 人类可读窗口 → token 数：128k→128000、1m→1000000、200k→200000、纯数字原样；无法解析返回 null */
    private static Integer parseWindow(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        try {
            if (s.endsWith("m")) {
                return (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000);
            }
            if (s.endsWith("k")) {
                return (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1000);
            }
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
