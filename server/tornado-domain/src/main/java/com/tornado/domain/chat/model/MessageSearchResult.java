package com.tornado.domain.chat.model;

import java.util.List;

/** 消息检索结果（命中总数 + 当前页命中）。 */
public record MessageSearchResult(long total, List<ChatMessageIndex> hits) {
}
