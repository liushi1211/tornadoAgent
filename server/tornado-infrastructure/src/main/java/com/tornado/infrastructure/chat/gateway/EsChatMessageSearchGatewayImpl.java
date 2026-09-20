package com.tornado.infrastructure.chat.gateway;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.domain.chat.gateway.ChatMessageSearchGateway;
import com.tornado.domain.chat.model.ChatMessageIndex;
import com.tornado.domain.chat.model.MessageSearchQuery;
import com.tornado.domain.chat.model.MessageSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息全文检索（Elasticsearch）实现。用 Jackson 组装 bool 查询 JSON 后 withJson 执行，
 * 避免不同 ES 客户端版本 RangeQuery API 差异；content 走 IK match，userId 强制过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsChatMessageSearchGatewayImpl implements ChatMessageSearchGateway {

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;

    @Override
    public MessageSearchResult search(MessageSearchQuery q) {
        int size = Math.min(Math.max(q.size(), 1), 100);
        int page = Math.max(q.page(), 1);
        int from = (page - 1) * size;

        List<Map<String, Object>> must = new ArrayList<>();
        List<Map<String, Object>> filters = new ArrayList<>();

        if (q.keyword() != null && !q.keyword().isBlank()) {
            must.add(Map.of("match", Map.of("content", q.keyword())));
        }
        if (q.userId() != null) {
            filters.add(Map.of("term", Map.of("userId", q.userId())));
        }
        if (q.sessionId() != null) {
            filters.add(Map.of("term", Map.of("sessionId", q.sessionId())));
        }
        if (q.role() != null && !q.role().isBlank()) {
            filters.add(Map.of("term", Map.of("role", q.role())));
        }
        boolean hasFrom = q.from() != null && !q.from().isBlank();
        boolean hasTo = q.to() != null && !q.to().isBlank();
        if (hasFrom || hasTo) {
            Map<String, Object> bound = new LinkedHashMap<>();
            if (hasFrom) {
                bound.put("gte", q.from());
            }
            if (hasTo) {
                bound.put("lte", q.to());
            }
            filters.add(Map.of("range", Map.of("createdAt", bound)));
        }

        Map<String, Object> bool = new LinkedHashMap<>();
        if (!must.isEmpty()) {
            bool.put("must", must);
        }
        if (!filters.isEmpty()) {
            bool.put("filter", filters);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("size", size);
        body.put("track_total_hits", true);
        body.put("sort", List.of(Map.of("createdAt", Map.of("order", "desc"))));
        body.put("query", Map.of("bool", bool));

        try {
            String json = objectMapper.writeValueAsString(body);
            SearchRequest req = SearchRequest.of(b -> b.index(EsChatMessageIndexGatewayImpl.INDEX)
                    .withJson(new StringReader(json)));
            SearchResponse<ChatMessageIndex> resp = client.search(req, ChatMessageIndex.class);

            long total = resp.hits().total() == null ? 0 : resp.hits().total().value();
            List<ChatMessageIndex> hits = new ArrayList<>();
            for (Hit<ChatMessageIndex> h : resp.hits().hits()) {
                if (h.source() != null) {
                    hits.add(h.source());
                }
            }
            return new MessageSearchResult(total, hits);
        } catch (Exception e) {
            log.error("ES 消息检索失败: {}", e.getMessage());
            throw new IllegalStateException("ES search failed", e);
        }
    }
}
