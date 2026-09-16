package com.tornado.infrastructure.rag.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** rag_document 表数据对象（MyBatis-Plus），仅存在于 infrastructure */
@Data
@TableName("rag_document")
public class RagDocumentDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String docType;
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
