package com.tornado.domain.chat.gateway;

import com.tornado.domain.chat.model.MessageSearchQuery;
import com.tornado.domain.chat.model.MessageSearchResult;

/** 消息全文检索网关（domain 端口，infrastructure 用 Elasticsearch 实现）。 */
public interface ChatMessageSearchGateway {

    MessageSearchResult search(MessageSearchQuery query);
}
