package com.tornado.client.rag.dto;

import lombok.Data;

/** RAG 检索命中项（对应前端 RagSearchHit：docId/seq/score/textSnippet/title） */
@Data
public class RagSearchHit {
    private Long docId;
    private Integer seq;
    private Double score;
    /** 命中文本片段 */
    private String textSnippet;
    /** 来源文档标题 */
    private String title;
}
