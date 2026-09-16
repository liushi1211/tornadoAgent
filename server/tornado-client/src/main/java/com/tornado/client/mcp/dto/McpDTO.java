package com.tornado.client.mcp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** MCP Server 配置展示对象（字段与原实体一致，保持 API 契约不变；不含 headers 密文） */
@Data
public class McpDTO {
    private Long id;
    private Long userId;
    private String name;
    private String transport;
    private String url;
    private String command;
    private String argsJson;
    private Integer enabled;
    private String healthStatus;
    private String toolCacheJson;
    private LocalDateTime lastProbeAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
