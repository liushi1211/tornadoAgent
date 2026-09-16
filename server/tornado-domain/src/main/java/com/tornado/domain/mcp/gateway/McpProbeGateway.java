package com.tornado.domain.mcp.gateway;

import com.tornado.domain.mcp.model.McpServer;

import java.util.List;

/**
 * MCP 探活网关（domain 定义，infra 用 McpJsonRpcClient 实现）。
 * 屏蔽 JSON-RPC 传输与 headers 解密细节。
 */
public interface McpProbeGateway {

    /** 探活结果：健康状态 + 工具快照 JSON + 工具名列表 */
    record ProbeResult(String healthStatus, String toolCacheJson, List<String> toolNames) {}

    /** 连接并 tools/list；失败抛异常由上层处理 */
    ProbeResult probe(McpServer server);
}
