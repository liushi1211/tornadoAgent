package com.tornado.client.mcp.cmd;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * MCP 批量导入命令：粘贴 Claude Desktop 风格配置
 * { "mcpServers": { "name": { "url": "...", "headers": {...}, "type": "sse|streamable_http" } } }。
 * transport 作为未显式声明 type 的条目的默认值；带 command 的 stdio 型会被跳过。
 */
@Data
public class McpImportCmd {
    @NotBlank
    private String json;
    /** streamable-http | sse（默认给未标 type 的条目） */
    private String transport = "streamable-http";
    /** 是否默认启用（enabled=1）；false 则导入后为停用待手动开启 */
    private Boolean enabled = false;
}
