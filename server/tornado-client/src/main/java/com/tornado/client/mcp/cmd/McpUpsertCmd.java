package com.tornado.client.mcp.cmd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

/** MCP Server 新增/更新命令 */
@Data
public class McpUpsertCmd {
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "名称需匹配 ^[A-Za-z0-9_-]{1,64}$")
    private String name;
    /** streamable-http | sse */
    @NotBlank
    private String transport = "streamable-http";
    private String url;
    private String command;
    private String argsJson;
    private Map<String, String> headers;
    private Integer enabled;
}
