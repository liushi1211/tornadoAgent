package com.tornado.boot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.common.entity.McpServerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话装配：把用户 enabled&HEALTHY MCP 的 tools/list 快照转成 ToolCallback。
 * inputSchema 原样透传（保证 DashScope arguments 为 JSON object），name 前缀 mcp_{server}_。
 * 每次调用现建短连接（streamable-http 无状态；SSE 传输建流-调用-关闭）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolFactory {

    private final McpServerService mcpServerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ToolCallback> toolCallbacks(Long uid) {
        List<ToolCallback> out = new ArrayList<>();
        for (McpServerConfig c : mcpServerService.activeHealthy(uid)) {
            if (c.getToolCacheJson() == null || c.getToolCacheJson().isBlank()) {
                continue;
            }
            try {
                JsonNode tools = objectMapper.readTree(c.getToolCacheJson());
                for (JsonNode t : tools) {
                    String toolName = t.path("name").asText();
                    String desc = t.path("description").asText("");
                    String schema = t.path("inputSchema").isMissingNode()
                            ? "{\"type\":\"object\",\"properties\":{}}" : t.path("inputSchema").toString();
                    out.add(buildCallback(sanitize(c.getName()) + "__" + sanitize(toolName), desc, schema, c, toolName));
                }
            } catch (Exception e) {
                log.warn("MCP {} 工具快照解析失败: {}", c.getName(), e.getMessage());
            }
        }
        return out;
    }

    private ToolCallback buildCallback(String name, String desc, String schema,
                                       McpServerConfig cfg, String remoteTool) {
        return FunctionToolCallback.builder(name, (JsonNode args) -> {
                    McpJsonRpcClient client = mcpServerService.buildClient(cfg);
                    try {
                        return client.callTool(remoteTool, args == null ? "{}" : args.toString());
                    } finally {
                        client.close();
                    }
                })
                .description(desc == null || desc.isBlank() ? "MCP tool " + remoteTool : desc)
                .inputType(JsonNode.class)
                .inputSchema(schema)
                .build();
    }

    private String sanitize(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
