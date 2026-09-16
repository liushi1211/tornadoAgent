package com.tornado.client.rag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** RAG 文档展示对象（字段与迁移前实体序列化保持一致，仅去掉 userId/filePath/deleted 等内部字段） */
@Data
public class RagDocumentDTO {
    private Long id;
    private String title;
    /** FILE|TEXT */
    private String docType;
    /** pdf|docx|md|txt */
    private String fileType;
    private Long sizeBytes;
    /** UPLOADED|PARSING|CHUNKED|EMBEDDING|READY|FAILED */
    private String status;
    private String errorMsg;
    private Integer chunkCount;
    private Integer tokenCount;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
