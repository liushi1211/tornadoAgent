package com.tornado.infrastructure.modelconfig;

import com.alibaba.cloud.nacos.annotation.NacosConfigListener;
import com.tornado.infrastructure.modelconfig.ChatModelProperties.ModelDef;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Nacos 模型清单热更新：nacos-client（gRPC 长连接）启动拉全量 + addListener 实时推送，
 * 配置变更秒级生效；另保留 5 分钟一次的对账轮询兜底（防连接抖动漏推送）。
 * saa.nacos.enabled=false 时该 Bean 不创建，仅用本地 application.yml 兜底。
 */
@Slf4j
@Component
public class NacosModelConfigLoader {

    @Autowired
    private ModelConfigHolder holder;
    @Autowired
    private NacosConfigs nacosConfigs;

    @NacosConfigListener(dataId = "chat-models.json", group = "DEFAULT_GROUP")
    public void onUserListChanged(ChatModelProperties chatModelProperties) {
        apply(chatModelProperties, "实时推送");
    }

    @PostConstruct
    public void init() {
        try {
            ChatModelProperties chatModelProperties = nacosConfigs.getChatModelProperties();
            apply(chatModelProperties, "启动加载");
            log.info("加载模型配置，默认模型: {}，现有模型={}",
                    chatModelProperties.getDefaultModel(), chatModelProperties.getModels().stream().map(ModelDef::getId).collect(Collectors.joining(",")));
        } catch (Exception e) {
            log.warn("Nacos 初始化失败，继续使用本地兜底配置: {}", e.getMessage());
        }
    }

    private void apply(ChatModelProperties chatModelProperties, String scene) {
        if (chatModelProperties == null || CollectionUtils.isEmpty(chatModelProperties.getModels())) {
            log.warn("Nacos {} 返回空内容，保持现有清单", scene);
            return;
        }
        List<ModelDef> defs = chatModelProperties.getModels();
        holder.replace(chatModelProperties);
        log.info("Nacos 模型清单已{}: {} 个模型", scene, defs.size());
    }
}
