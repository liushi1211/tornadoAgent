package com.tornado.domain.rag.model;

/** ES BM25 关键词召回命中（领域值对象）。 */
public record RagChunkHit(Long docId, int seq, double score, String text, String title) {
}
