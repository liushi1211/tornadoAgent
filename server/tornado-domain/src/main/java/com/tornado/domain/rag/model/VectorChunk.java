package com.tornado.domain.rag.model;

/** 待写入向量库的切片（领域值对象）；向量库主键/元数据映射由 infrastructure 负责 */
public record VectorChunk(Long docId, int seq, Long userId, String source, String text) {
}
