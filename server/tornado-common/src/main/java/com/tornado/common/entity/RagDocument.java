package com.tornado.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** RAG 文档元数据（状态机 UPLOADED→PARSING→CHUNKED→EMBEDDING→READY/FAILED） */
@Data
@TableName("rag_document")
public class RagDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    /** FILE|TEXT */
    private String docType;
    /** pdf|docx|md|txt */
    private String fileType;
    private String filePath;
    private Long sizeBytes;
    private String status;
    private String errorMsg;
    private Integer chunkCount;
    private Integer tokenCount;
    private Integer retryCount;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
