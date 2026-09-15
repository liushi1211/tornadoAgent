package com.tornado.boot.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** saa.rag.* 配置 */
@Data
@Component
@ConfigurationProperties(prefix = "saa.rag")
public class RagProperties {
    /** 上传文件本地存储目录 */
    private String uploadDir = "./data/rag-files";
    /** 向量库连接（Redis Stack） */
    private String redisHost = "localhost";
    private int redisPort = 6379;
    private String redisPassword = "";
    /** RediSearch 索引名 */
    private String indexName = "saa_rag_idx";
    /** 向量 key 前缀 */
    private String prefix = "rag:doc";
    /** 切分窗口/重叠（字符） */
    private int chunkChars = 1600;
    private int chunkOverlap = 200;
    /** 检索默认 topK */
    private int topk = 20;
    /** rerank 占位开关（当前版本默认关、未实现重排） */
    private boolean rerankEnabled = false;
    /** 单文件上限 MB */
    private long maxFileSizeMb = 20;
}
