package com.tornado.client.mcp.dto;

import java.util.List;

/** MCP 探活结果 */
public record McpTestResult(String status, List<String> tools) {}
