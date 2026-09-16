package com.tornado.domain.mcp.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * MCP Server 配置聚合根。
 * headersCipher 为不透明密文（由 infra 用 CryptoService 生成/解读）；plainHeaders 仅创建/更新时瞬时携带，不持久化于领域逻辑。
 */
@Data
public class McpServer {
    private Long id;
    private Long userId;
    private String name;
    /** streamable-http | sse */
    private String transport;
    private String url;
    private String command;
    private String argsJson;
    private byte[] headersCipher;
    /** 明文 headers（仅入参瞬时使用） */
    private Map<String, String> plainHeaders;
    private Integer enabled;
    /** UNKNOWN|HEALTHY|DOWN */
    private String healthStatus;
    private String toolCacheJson;
    private LocalDateTime lastProbeAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isEnabled() {
        return enabled != null && enabled == 1;
    }

    public boolean isHealthy() {
        return "HEALTHY".equals(healthStatus);
    }

    public void setEnabledFlag(boolean on) {
        this.enabled = on ? 1 : 0;
    }

    public void applyProbe(String status, String snapshotJson) {
        this.healthStatus = status;
        this.toolCacheJson = snapshotJson;
        this.lastProbeAt = LocalDateTime.now();
    }
}
