package com.tornado.infrastructure.rag.gateway;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.domain.rag.gateway.RagChunkIndexGateway;
import com.tornado.domain.rag.model.RagChunkHit;
import com.tornado.domain.rag.model.RagChunkIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RAG 切片 ES 索引实现：canal 消费者灌数（bulk），检索用 BM25（IK 分词，userId 过滤）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsRagChunkGatewayImpl implements RagChunkIndexGateway {

    public static final String INDEX = "tornado_rag_chunk";

    private static final String MAPPING = """
            {
              "mappings": {
                "properties": {
                  "docId":  { "type": "long" },
                  "userId": { "type": "long" },
                  "seq":    { "type": "integer" },
                  "title":  { "type": "text", "fields": { "kw": { "type": "keyword" } } },
                  "content":{ "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" }
                }
              }
            }
            """;

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;

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
    public void bulkUpsert(List<RagChunkIndex> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            List<BulkOperation> ops = docs.stream()
                    .map(d -> BulkOperation.of(o -> o.index(i -> i.index(INDEX).id(d.id()).document(d))))
                    .toList();
            client.bulk(b -> b.operations(ops));
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

    @Override
    public List<RagChunkHit> bm25(Long userId, String query, int topK) {
        Map<String, Object> must = Map.of("match", Map.of("content", query));
        List<Map<String, Object>> filters = new ArrayList<>();
        if (userId != null) {
            filters.add(Map.of("term", Map.of("userId", userId)));
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", List.of(must));
        if (!filters.isEmpty()) {
            bool.put("filter", filters);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", topK);
        body.put("query", Map.of("bool", bool));
        try {
            String json = objectMapper.writeValueAsString(body);
            SearchRequest req = SearchRequest.of(b -> b.index(INDEX).withJson(new StringReader(json)));
            SearchResponse<RagChunkIndex> resp = client.search(req, RagChunkIndex.class);
            List<RagChunkHit> hits = new ArrayList<>();
            for (Hit<RagChunkIndex> h : resp.hits().hits()) {
                RagChunkIndex s = h.source();
                if (s == null) {
                    continue;
                }
                hits.add(new RagChunkHit(s.docId(), s.seq(),
                        h.score() == null ? 0d : h.score(), s.content(), s.title()));
            }
            return hits;
        } catch (Exception e) {
            log.warn("ES BM25 检索失败（降级为空）: {}", e.getMessage());
            return List.of();
        }
    }
}
