package com.tornado.domain.rag.model;

/** 写入 ES 的 RAG 切片索引文档（值对象）。id = "chunk_{docId}_{seq}"。 */
public record RagChunkIndex(
        String id,
        Long docId,
        Long userId,
        int seq,
        String title,
        String content) {

    public static String docId(Long docId, int seq) {
        return "chunk_" + docId + "_" + seq;
    }
}
