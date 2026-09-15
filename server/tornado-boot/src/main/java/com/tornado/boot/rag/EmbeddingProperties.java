package com.tornado.boot.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** saa.embedding.* 配置（DashScope OpenAI 兼容模式 embedding） */
@Data
@Component
@ConfigurationProperties(prefix = "saa.embedding")
public class EmbeddingProperties {
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
    private String apiKey = "";
    private String model = "text-embedding-v4";
    private int dimensions = 1024;
}
