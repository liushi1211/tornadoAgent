package com.tornado.domain.rag.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 文档聚合根（纯领域对象）。状态机：UPLOADED→PARSING→CHUNKED→EMBEDDING→READY / FAILED。
 * 迁移自 common.entity.RagDocument；deleted 软删标记属于持久化细节，只保留在 infra DO 中。
 */
@Data
public class RagDocument {

    /** 处理中（禁止删除/重试）的中间态集合 */
    public static final String ST_UPLOADED = "UPLOADED";
    public static final String ST_PARSING = "PARSING";
    public static final String ST_CHUNKED = "CHUNKED";
    public static final String ST_EMBEDDING = "EMBEDDING";
    public static final String ST_READY = "READY";
    public static final String ST_FAILED = "FAILED";
    private static final List<String> IN_PROGRESS = List.of(ST_UPLOADED, ST_PARSING, ST_CHUNKED, ST_EMBEDDING);
    public static final int MAX_RETRY = 3;

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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public int chunkCountOrZero() {
        return chunkCount == null ? 0 : chunkCount;
    }

    public int retryCountOrZero() {
        return retryCount == null ? 0 : retryCount;
    }

    /** 是否处于处理中（上传/解析/切分/向量写入），此时不允许删除或重试 */
    public boolean isInProgress() {
        return status != null && IN_PROGRESS.contains(status);
    }

    /** 仅 FAILED 且重试次数未用尽才可重试 */
    public boolean canRetry() {
        return ST_FAILED.equals(status) && retryCountOrZero() < MAX_RETRY;
    }

    public void begin(String status) {
        this.status = status;
        this.errorMsg = null;
    }

    /** 转入失败态：截断错误信息避免超长文本入库 */
    public void markFailed(String err) {
        this.status = ST_FAILED;
        String msg = err == null ? "" : err;
        this.errorMsg = msg.length() <= 480 ? msg : msg.substring(0, 480);
    }

    public void markReady(int chunks, int tokens) {
        this.status = ST_READY;
        this.chunkCount = chunks;
        this.tokenCount = tokens;
        this.errorMsg = null;
    }
}
