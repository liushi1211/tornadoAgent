package com.tornado.app.chat.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具调用拦截器：记录每次工具调用入参，异常时转成工具响应回喂模型。
 * 注：项目未引入 fastjson，这里用 request 自带字段拼接，避免额外依赖。
 */
@Component
@Slf4j
public class ToolInvokeInterceptor extends ToolInterceptor {

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        try {
            log.info("工具调用进入: name={}, args={}", request.getToolName(), request.getArguments());
            return handler.call(request);
        } catch (Exception e) {
            log.warn("工具调用异常: name={}, err={}", request.getToolName(), e.getMessage());
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                    "Tool failed: " + e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "tool-invoke-interceptor";
    }
}
