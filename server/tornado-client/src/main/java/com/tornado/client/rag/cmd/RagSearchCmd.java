package com.tornado.client.rag.cmd;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 知识库检索命令 */
@Data
public class RagSearchCmd {
    @NotBlank
    private String query;
    private Integer topK;
    /** 重排开关（当前版本占位，默认关） */
    private Boolean rerank;
}
