package com.tornado.domain.rag.model;

/** 向量检索命中（领域值对象）：文档 id、切片序号、相似度、正文、来源标题 */
public record VectorHit(Long docId, int seq, double score, String text, String source) {
}
