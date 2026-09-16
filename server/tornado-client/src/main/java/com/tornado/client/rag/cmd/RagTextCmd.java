package com.tornado.client.rag.cmd;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 粘贴文本入库命令 */
@Data
public class RagTextCmd {
    private String title;
    @NotBlank
    private String content;
}
