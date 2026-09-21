package com.tornado.domain.rag.model;

import lombok.Data;

import java.time.LocalDateTime;

/** RAG 切片（正文落库，供 canal→ES 做 BM25 关键词召回；与向量库并行）。 */
@Data
public class RagChunk {
    private Long id;
    private Long docId;
    private Long userId;
    private Integer seq;
    private String title;
    private String content;
    private LocalDateTime createdAt;
}
