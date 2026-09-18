package com.tornado.infrastructure.modelconfig;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型清单配置载体：Nacos chat-models.json 反序列化目标（本地兜底默认值 + 远端整体替换）。
 * 属于基础设施的数据结构；对外领域视图见 domain.modelconfig.model.ModelDefinition。
 */
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
        /** 模型最大输入上下文，人类可读：如 128k / 1m / 200k（也接受纯数字串）；缺省按 128000 */
        private String contextWindow;
    }
}
