package com.tornado.boot.Interceptors;


import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型调用拦截器：把本轮真正发给大模型的完整上下文打印出来（调试利器）。
 * 内容包括：System Prompt、逐条消息（角色/正文/工具调用/工具返回）、可用工具清单。
 * 开关：saa.log.llm-context=true 走 INFO；否则只在 DEBUG 级别输出。
 * 注意 ReAct 每轮模型调用都会经过这里——工具循环的第 2、3 次调用同样会打印，
 * 正好能看到工具结果是如何拼回上下文再次送模的。
 */
@Slf4j
@Component
public class UserMessageModelInterceptor extends ModelInterceptor {

    /** 单条消息最大打印长度，防止知识库长文把日志刷爆 */
    private static final int MAX_PER_MSG = 1500;

    @Value("${saa.log.llm-context:true}")
    private boolean logContext;

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String dump = render(request);
        if (logContext) {
            log.info("发给模型的完整上下文 ↓\n{}", dump);
        } else {
            log.debug("发给模型的完整上下文 ↓\n{}", dump);
        }
        return handler.call(request);
    }

    private String render(ModelRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SYSTEM ===\n");
        sb.append(r.getSystemMessage() == null ? "(无)" : cut(r.getSystemMessage().getText())).append('\n');

        List<Message> messages = r.getMessages();
        sb.append("=== MESSAGES (").append(messages == null ? 0 : messages.size()).append(" 条) ===\n");
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                MessageType type = m.getMessageType();
                sb.append('[').append(i).append("] ").append(type.getValue().toUpperCase());
                if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                    sb.append(" → 请求调用工具: ");
                    am.getToolCalls().forEach(tc ->
                            sb.append(tc.name()).append(cut(tc.arguments(), 300)).append(' '));
                } else if (m instanceof ToolResponseMessage trm) {
                    sb.append(" ← 工具返回: ").append('\n');
                    trm.getResponses().forEach(tr ->
                            sb.append("      ").append(tr.name()).append(" = ").append(cut(tr.responseData(), 500)).append('\n'));
                    continue;
                }
                sb.append(": ").append(cut(m.getText())).append('\n');
            }
        }
        sb.append("=== TOOLS (").append(r.getTools() == null ? 0 : r.getTools().size()).append(") ===\n");
        if (r.getTools() != null && !r.getTools().isEmpty()) {
            r.getTools().forEach(v->{
                sb.append(v).append(": ").append(r.getToolDescriptions().get(v)).append('\n');

            });
        }
        return sb.toString();
    }

    private String cut(String s) {
        return cut(s, MAX_PER_MSG);
    }

    private String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(截断,共" + s.length() + "字)";
    }

    @Override
    public String getName() {
        return "user-message-model-interceptor";
    }
}
