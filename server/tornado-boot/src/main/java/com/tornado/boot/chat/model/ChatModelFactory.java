package com.tornado.boot.chat.model;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.tornado.common.ex.BizException;
import com.tornado.common.ex.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 provider 编程式构建 ChatModel（阻塞走 RestClient、流式走 WebClient 双通道），按 id 缓存 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    private final ModelConfigHolder holder;
    private final RestClient.Builder restClientBuilder;
    private final WebClient.Builder webClientBuilder;

    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();
    private volatile long cacheVersion = -1;

    public synchronized ChatModel get(String modelId) {
        if (cacheVersion != holder.version()) {
            cache.clear();
            cacheVersion = holder.version();
        }
        ChatModelProperties.ModelDef def = holder.findOrDefault(modelId);
        if (def == null) {
            throw new BizException(ErrorCode.CHAT_MODEL_UNKNOWN, "未知模型: " + modelId);
        }
        return cache.computeIfAbsent(cacheKey(def), k -> build(def));
    }

    public String resolveModelId(String modelId) {
        ChatModelProperties.ModelDef def = holder.findOrDefault(modelId);
        if (def == null) {
            throw new BizException(ErrorCode.CHAT_MODEL_UNKNOWN, "未知模型: " + modelId);
        }
        return def.getId();
    }

    private String cacheKey(ChatModelProperties.ModelDef d) {
        return d.getId() + "|" + (d.getApiKey() == null ? "" : d.getApiKey().hashCode())
                + "|" + (d.getBaseUrl() == null ? "" : d.getBaseUrl());
    }

    private ChatModel build(ChatModelProperties.ModelDef def) {
        String apiKey = resolveApiKey(def);
        if ("dashscope".equalsIgnoreCase(def.getProvider())) {
            DashScopeApi api = DashScopeApi.builder()
                    .apiKey(apiKey)
                    .restClientBuilder(restClientBuilder)
                    .webClientBuilder(webClientBuilder)
                    .build();
            DashScopeChatOptions options = new DashScopeChatOptions();
            options.setModel(def.getId());
            if (def.getTemperature() != null) {
                options.setTemperature(def.getTemperature());
            }
            if (def.getMaxTokens() != null) {
                options.setMaxTokens(def.getMaxTokens());
            }
            return DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(options).build();
        }
        // openai-compatible（OpenAI 协议端点：兼容模式 / Ollama / vLLM 等）
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder);
        if (def.getBaseUrl() != null && !def.getBaseUrl().isBlank()) {
            apiBuilder.baseUrl(def.getBaseUrl());
        }
        OpenAiChatOptions.Builder ob = OpenAiChatOptions.builder().model(def.getId());
        if (def.getTemperature() != null) {
            ob.temperature(def.getTemperature());
        }
        if (def.getMaxTokens() != null) {
            ob.maxTokens(def.getMaxTokens());
        }
        return OpenAiChatModel.builder().openAiApi(apiBuilder.build()).defaultOptions(ob.build()).build();
    }

    private String resolveApiKey(ChatModelProperties.ModelDef def) {
        String k = def.getApiKey();
        if (k == null || k.isBlank()) {
            k = System.getenv("dashscope".equalsIgnoreCase(def.getProvider())
                    ? "AI_DASHSCOPE_API_KEY" : "SAA_OPENAI_API_KEY");
        }
        if (k == null || k.isBlank()) {
            k = "sk-empty-placeholder";
            log.warn("模型 {} 未配置 api-key，使用占位符，调用将失败", def.getId());
        }
        return k;
    }
}
