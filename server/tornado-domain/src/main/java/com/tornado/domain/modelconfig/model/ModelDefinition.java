package com.tornado.domain.modelconfig.model;

import lombok.Data;

/** 模型定义值对象（纯领域，零框架依赖）：描述一个可用大模型端点的元数据，供 ChatModelFactory 编程式构建 ChatModel */
@Data
public class ModelDefinition {
    private String id;
    /** dashscope | openai-compatible */
    private String provider;
    private String apiKey;
    private String baseUrl;
    private String displayName;
    private boolean supportsThinking;
    private Double temperature;
    private Integer maxTokens;
}
