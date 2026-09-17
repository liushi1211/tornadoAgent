package com.tornado.infrastructure.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.infrastructure.common.crypto.CryptoService;
import com.tornado.domain.mcp.model.McpServer;
import com.tornado.domain.mcp.repository.McpServerRepository;
import com.tornado.infrastructure.mcp.client.McpJsonRpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话装配：把用户 enabled&HEALTHY MCP 的 tools/list 快照转成 Spring AI ToolCallback。
 * 返回框架类型，故作为 infrastructure 组件被 chat 运行时直接依赖。
 * inputSchema 原样透传（保证 DashScope arguments 为 JSON object），name 前缀 {server}__{tool}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolFactory {

    private final McpServerRepository mcpServerRepository;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ToolCallback> toolCallbacks(Long uid) {
        List<ToolCallback> out = new ArrayList<>();
        for (McpServer c : mcpServerRepository.listActiveHealthy(uid)) {
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
                                       McpServer cfg, String remoteTool) {
        return FunctionToolCallback.builder(name, (JsonNode args) -> {
                    McpJsonRpcClient client = new McpJsonRpcClient(cfg.getTransport(), cfg.getUrl(), decryptHeaders(cfg));
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

    @SuppressWarnings("unchecked")
    private Map<String, String> decryptHeaders(McpServer c) {
        if (c.getHeadersCipher() == null || c.getHeadersCipher().length == 0) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(cryptoService.decryptStr(c.getHeadersCipher()), Map.class);
        } catch (Exception e) {
            log.warn("MCP headers 解密失败 id={}", c.getId());
            return Map.of();
        }
    }

    private String sanitize(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
