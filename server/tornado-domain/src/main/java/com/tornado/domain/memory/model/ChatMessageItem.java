package com.tornado.domain.memory.model;

/**
 * 短期记忆里的单条消息（框架无关值对象）：role 取 user|assistant|system。
 * 与 Spring AI Message 的相互转换封装在 infrastructure 的 ChatMemoryGateway 实现里，领域/应用层不感知框架类型。
 */
public record ChatMessageItem(String role, String text) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
}
