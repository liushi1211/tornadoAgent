package com.tornado.infrastructure.rag.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * 自建 Embedding / RedisVectorStore Bean（starter 自动配置为 @ConditionalOnMissingBean，会被此处覆盖）。
 * 索引维度与 embedding 模型维度一致（1024）；metadata 必含 user_id 做租户隔离。
 */
@Configuration
public class RagStoreConfig {

    @Bean
    public EmbeddingModel embeddingModel(EmbeddingProperties props) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(resolveKey(props))
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(props.getModel())
                .dimensions(props.getDimensions())
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel, RagProperties ragProps) {
        JedisPooled jedis = ragProps.getRedisPassword() == null || ragProps.getRedisPassword().isBlank()
                ? new JedisPooled(ragProps.getRedisHost(), ragProps.getRedisPort())
                : new JedisPooled(new HostAndPort(ragProps.getRedisHost(), ragProps.getRedisPort()),
                        DefaultJedisClientConfig.builder().password(ragProps.getRedisPassword()).build());
        return RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(ragProps.getIndexName())
                .prefix(ragProps.getPrefix())
                .initializeSchema(true)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("user_id"),
                        RedisVectorStore.MetadataField.tag("doc_id"),
                        RedisVectorStore.MetadataField.numeric("seq"),
                        RedisVectorStore.MetadataField.text("source"))
                .build();
    }

    private String resolveKey(EmbeddingProperties props) {
        String k = props.getApiKey();
        if (k == null || k.isBlank()) {
            k = System.getenv("SAA_KEY_DASHSCOPE");
        }
        if (k == null || k.isBlank()) {
            k = System.getenv("AI_DASHSCOPE_API_KEY");
        }
        return k == null || k.isBlank() ? "sk-empty-placeholder" : k;
    }
}
