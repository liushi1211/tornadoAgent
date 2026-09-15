package com.tornado.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** MCP Server 配置（headers_cipher 为 AES-GCM 密文；删除采用物理删除） */
@Data
@TableName("mcp_server_config")
public class McpServerConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    /** SSE|STREAMABLE_HTTP */
    private String transport;
    private String url;
    private String command;
    private String argsJson;
    private byte[] headersCipher;
    private Integer enabled;
    /** UNKNOWN|HEALTHY|DOWN */
    private String healthStatus;
    /** 最近一次 tools/list 快照 */
    private String toolCacheJson;
    private LocalDateTime lastProbeAt;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
