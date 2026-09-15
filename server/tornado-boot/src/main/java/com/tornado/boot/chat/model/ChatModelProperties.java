package com.tornado.boot.chat.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 本地兜底模型清单：chat.default-model + chat.models[]（Nacos 可用时被远端整体替换） */
@Data
public class ChatModelProperties {
    private String defaultModel = "qwen-plus";
    private List<ModelDef> models = new ArrayList<>();

    @Data
    public static class ModelDef {
        private String id;
        /** dashscope | openai-compatible */
        private String provider = "dashscope";
        private String apiKey;
        private String baseUrl;
        private String displayName;
        private boolean supportsThinking;
        private Double temperature;
        private Integer maxTokens;
    }
}
