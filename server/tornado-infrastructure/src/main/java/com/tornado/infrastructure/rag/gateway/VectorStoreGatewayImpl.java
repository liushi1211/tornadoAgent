package com.tornado.infrastructure.rag.gateway;

import com.tornado.domain.rag.gateway.VectorStoreGateway;
import com.tornado.domain.rag.model.VectorChunk;
import com.tornado.domain.rag.model.VectorHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量库网关实现：封装 Spring AI VectorStore 与切片主键约定 doc{id}c{seq}、metadata 租户字段。
 * 检索强制 user_id 过滤，保证跨用户隔离。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorStoreGatewayImpl implements VectorStoreGateway {

    private final VectorStore vectorStore;

    @Override
    public void add(List<VectorChunk> chunks) throws Exception {
        List<Document> docs = new ArrayList<>(chunks.size());
        for (VectorChunk c : chunks) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("user_id", String.valueOf(c.userId()));
            meta.put("doc_id", String.valueOf(c.docId()));
            meta.put("seq", c.seq());
            meta.put("source", c.source());
            docs.add(Document.builder()
                    .id(vectorId(c.docId(), c.seq()))
                    .text(c.text())
                    .metadata(meta)
                    .build());
        }
        vectorStore.add(docs);
    }

    @Override
    public void deleteByDocument(Long docId, int chunkCount) {
        if (chunkCount <= 0) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            ids.add(vectorId(docId, i));
        }
        try {
            vectorStore.delete(ids);
        } catch (Exception e) {
            log.warn("向量删除失败 docId={}: {}", docId, e.getMessage());
        }
    }

    @Override
    public List<VectorHit> search(String query, Long userId, int topK) {
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression("user_id == '" + userId + "'")
                .build());
        if (docs == null) {
            return List.of();
        }
        List<VectorHit> out = new ArrayList<>(docs.size());
        for (Document d : docs) {
            Map<String, Object> m = d.getMetadata();
            out.add(new VectorHit(
                    parseLong(String.valueOf(m.getOrDefault("doc_id", "0"))),
                    m.get("seq") == null ? 0 : (int) Double.parseDouble(String.valueOf(m.get("seq"))),
                    d.getScore() == null ? 0d : d.getScore(),
                    d.getText(),
                    String.valueOf(m.getOrDefault("source", ""))));
        }
        return out;
    }

    private String vectorId(Long docId, int seq) {
        return "doc" + docId + "c" + seq;
    }

    private Long parseLong(String s) {
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
