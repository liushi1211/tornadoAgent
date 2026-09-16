package com.tornado.client.memory.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 长期记忆展示对象（对应前端 MemoryVO） */
@Data
public class MemoryDTO {
    private Long id;
    /** preference|fact|summary */
    private String category;
    private String content;
    private Long sourceSessionId;
    private Integer hitCount;
    /** 1 有效 2 已合并淘汰 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
