package com.tornado.infrastructure.rag.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** rag_chunk 表数据对象（切片正文落库，供 canal→ES BM25），仅存在于 infrastructure */
@Data
@TableName("rag_chunk")
public class RagChunkDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long userId;
    private Integer seq;
    private String title;
    private String content;
    private LocalDateTime createdAt;
}
