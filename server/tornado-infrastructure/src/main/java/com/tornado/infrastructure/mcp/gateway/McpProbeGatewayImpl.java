package com.tornado.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.infrastructure.common.crypto.CryptoService;
import com.tornado.domain.mcp.gateway.McpProbeGateway;
import com.tornado.domain.mcp.model.McpServer;
import com.tornado.infrastructure.mcp.client.McpJsonRpcClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP 探活网关实现：解密 headers → JSON-RPC initialize/tools/list → 生成工具快照 */
@Component
@RequiredArgsConstructor
public class McpProbeGatewayImpl implements McpProbeGateway {

    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ProbeResult probe(McpServer server) {
        McpJsonRpcClient client = new McpJsonRpcClient(server.getTransport(), server.getUrl(),
                McpHeadersCipher.decrypt(cryptoService, objectMapper, server.getHeadersCipher()));
        try {
            List<McpJsonRpcClient.McpTool> tools = client.probe();
            List<Map<String, Object>> snapshot = new ArrayList<>();
            for (McpJsonRpcClient.McpTool t : tools) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", t.name());
                item.put("description", t.description() == null ? "" : t.description());
                item.put("inputSchema", objectMapper.readValue(
                        t.inputSchema().isMissingNode() || t.inputSchema().isNull()
                                ? "{\"type\":\"object\",\"properties\":{}}" : t.inputSchema().toString(),
                        Map.class));
                snapshot.add(item);
            }
            return new ProbeResult("HEALTHY", objectMapper.writeValueAsString(snapshot),
                    tools.stream().map(McpJsonRpcClient.McpTool::name).toList());
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            client.close();
        }
    }

    /** headers 密文解密的共享工具 */
    static final class McpHeadersCipher {
        private McpHeadersCipher() {}

        @SuppressWarnings("unchecked")
        static Map<String, String> decrypt(CryptoService crypto, ObjectMapper om, byte[] cipher) {
            if (cipher == null || cipher.length == 0) {
                return Map.of();
            }
            try {
                return om.readValue(crypto.decryptStr(cipher), Map.class);
            } catch (Exception e) {
                return Map.of();
            }
        }
    }
}
