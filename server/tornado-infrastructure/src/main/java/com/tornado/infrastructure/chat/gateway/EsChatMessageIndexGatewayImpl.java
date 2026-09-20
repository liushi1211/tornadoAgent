package com.tornado.infrastructure.chat.gateway;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.tornado.domain.chat.gateway.ChatMessageIndexGateway;
import com.tornado.domain.chat.model.ChatMessageIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.List;

/**
 * 消息检索索引（Elasticsearch）实现。索引 tornado_chat_message，content/thinking 用 IK 中文分词。
 * 由应用内嵌的 canal 消费者灌数；ensureIndex 幂等建索引。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsChatMessageIndexGatewayImpl implements ChatMessageIndexGateway {

    public static final String INDEX = "tornado_chat_message";

    private static final String MAPPING = """
            {
              "mappings": {
                "properties": {
                  "userId":       { "type": "long" },
                  "sessionId":    { "type": "long" },
                  "role":         { "type": "keyword" },
                  "content":      { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "thinking":     { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "toolCalls":    { "type": "text", "index": false },
                  "finishReason": { "type": "keyword" },
                  "createdAt":    { "type": "date", "format": "strict_date_optional_time||yyyy-MM-dd HH:mm:ss||yyyy-MM-dd HH:mm:ss.SSS" }
                }
              }
            }
            """;

    private final ElasticsearchClient client;

    @Override
    public void ensureIndex() {
        try {
            boolean exists = client.indices().exists(e -> e.index(INDEX)).value();
            if (!exists) {
                client.indices().create(c -> c.index(INDEX).withJson(new StringReader(MAPPING)));
                log.info("ES 索引已创建: {}", INDEX);
            }
        } catch (Exception e) {
            log.error("确保 ES 索引失败 {}: {}", INDEX, e.getMessage());
            throw new IllegalStateException("ES ensureIndex failed", e);
        }
    }

    @Override
    public void bulkUpsert(List<ChatMessageIndex> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            List<BulkOperation> ops = docs.stream()
                    .map(d -> BulkOperation.of(o -> o.index(i -> i.index(INDEX).id(d.id()).document(d))))
                    .toList();
            var resp = client.bulk(b -> b.operations(ops));
            if (resp.errors()) {
                log.warn("ES bulk upsert 部分失败，count={}, 首个错误={}", docs.size(),
                        resp.items().stream().filter(it -> it.error() != null).findFirst()
                                .map(it -> it.error().reason()).orElse(""));
            }
        } catch (Exception e) {
            throw new IllegalStateException("ES bulkUpsert failed", e);
        }
    }

    @Override
    public void bulkDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            List<BulkOperation> ops = ids.stream()
                    .map(id -> BulkOperation.of(o -> o.delete(d -> d.index(INDEX).id(id))))
                    .toList();
            client.bulk(b -> b.operations(ops));
        } catch (Exception e) {
            throw new IllegalStateException("ES bulkDelete failed", e);
        }
    }
}
