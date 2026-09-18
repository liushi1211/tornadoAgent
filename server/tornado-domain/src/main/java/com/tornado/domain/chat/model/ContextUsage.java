package com.tornado.domain.chat.model;

/** 某会话最近一轮发给模型的上下文占用快照（估算 token 数 + 所用模型 + 时间戳） */
public record ContextUsage(String modelId, int usedTokens, long ts) {
}
