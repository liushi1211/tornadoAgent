package com.tornado.infrastructure.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * 极简 MCP 客户端（JSON-RPC 2.0 over HTTP），编译稳定优先，不依赖 io.modelcontextprotocol SDK。
 * 支持两种 transport：
 *  - streamable-http：直接 POST JSON-RPC 到 url（响应为 JSON 或 SSE data 帧），携带 Mcp-Session-Id；
 *  - sse（legacy HTTP+SSE）：GET url 建流拿 endpoint 事件，POST 到 endpoint，响应从 SSE 流按 id 匹配。
 */
@Slf4j
public class McpJsonRpcClient {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    public record McpTool(String name, String description, JsonNode inputSchema) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final String transport;
    private final String url;
    private final Map<String, String> headers;
    private volatile String sessionId;
    private int nextId = 1;

    public McpJsonRpcClient(String transport, String url, Map<String, String> headers) {
        this.transport = transport == null ? "streamable-http" : transport.toLowerCase();
        this.url = url;
        this.headers = headers == null ? Map.of() : headers;
    }

    /** 探活：initialize + tools/list */
    public List<McpTool> probe() {
        ObjectNode params = OM.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "tornado-agent");
        clientInfo.put("version", "1.0.0");
        params.putArray("capabilities");
        JsonNode initResult = rpc("initialize", params);
        log.debug("MCP initialize 结果: {}", initResult);
        notify("notifications/initialized", OM.createObjectNode());
        JsonNode tools = rpc("tools/list", OM.createObjectNode());
        List<McpTool> out = new ArrayList<>();
        if (tools != null && tools.has("tools")) {
            for (JsonNode t : tools.get("tools")) {
                out.add(new McpTool(t.path("name").asText(), t.path("description").asText(""),
                        t.path("inputSchema")));
            }
        }
        return out;
    }

    /** 工具调用，返回 text 内容拼接 */
    public String callTool(String toolName, String argsJson) {
        ObjectNode params = OM.createObjectNode();
        params.put("name", toolName);
        try {
            params.set("arguments", argsJson == null || argsJson.isBlank()
                    ? OM.createObjectNode() : OM.readTree(argsJson));
        } catch (Exception e) {
            throw new IllegalStateException("工具参数非法 JSON: " + e.getMessage(), e);
        }
        JsonNode result = rpc("tools/call", params);
        if (result == null) {
            return "{}";
        }
        JsonNode content = result.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : content) {
                if (c.has("text")) {
                    sb.append(c.get("text").asText()).append('\n');
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        return result.toString();
    }

    // ---------- 传输实现 ----------

    private synchronized JsonNode rpc(String method, ObjectNode params) {
        ObjectNode req = OM.createObjectNode();
        req.put("jsonrpc", "2.0");
        int id = nextId++;
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);
        try {
            if (transport.startsWith("sse")) {
                return overLegacySse(req.toString(), id);
            }
            return overStreamableHttp(req.toString(), id, true);
        } catch (Exception e) {
            throw new IllegalStateException("MCP 调用失败(" + method + "): " + e.getMessage(), e);
        }
    }

    private synchronized void notify(String method, ObjectNode params) {
        ObjectNode req = OM.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        req.set("params", params);
        try {
            if (transport.startsWith("sse")) {
                post(resolveEndpoint(), req.toString());
            } else {
                overStreamableHttp(req.toString(), -1, false);
            }
        } catch (Exception e) {
            log.debug("MCP notification {} 失败（忽略）: {}", method, e.getMessage());
        }
    }

    /** streamable-http：POST 后响应既兼容纯 JSON 也兼容 SSE 帧 */
    private JsonNode overStreamableHttp(String body, int id, boolean wantResponse) throws Exception {
        String resp = post(url, body);
        if (!wantResponse) {
            return null;
        }
        JsonNode node = extractJsonFrom(resp, id);
        if (node != null && node.has("error")) {
            throw new IllegalStateException(node.get("error").toString());
        }
        return node == null ? null : node.path("result");
    }

    private String post(String target, String body) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(target))
                .timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(rb::header);
        if (sessionId != null) {
            rb.header("Mcp-Session-Id", sessionId);
        }
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /** 从纯 JSON 或 SSE 文本中取出目标 id 的 jsonrpc 响应 */
    private JsonNode extractJsonFrom(String resp, int id) throws Exception {
        if (resp == null || resp.isBlank()) {
            return null;
        }
        String trimmed = resp.trim();
        if (trimmed.startsWith("{")) {
            return OM.readTree(trimmed);
        }
        JsonNode fallback = null;
        for (String line : trimmed.split("\n")) {
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                try {
                    JsonNode node = OM.readTree(data);
                    if (node.has("id") && node.get("id").asInt() == id) {
                        return node;
                    }
                    if (node.has("result") || node.has("error")) {
                        fallback = node;
                    }
                } catch (Exception ignore) {
                    // 非 JSON 帧跳过
                }
            }
        }
        return fallback;
    }

    // ---------- legacy SSE transport ----------

    private volatile String endpointUrl;
    private final BlockingQueue<String> sseData = new LinkedBlockingQueue<>();
    private final SynchronousQueue<String> endpointSignal = new SynchronousQueue<>();
    private final Map<Integer, JsonNode> responses = new ConcurrentHashMap<>();
    private volatile Thread sseReaderThread;
    private volatile InputStream sseStream;

    private JsonNode overLegacySse(String body, int id) throws Exception {
        ensureStream();
        post(resolveEndpoint(), body);
        // 响应经 SSE 流异步分发；先轮询 responses
        long deadline = System.currentTimeMillis() + CALL_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            JsonNode node = responses.remove(id);
            if (node != null) {
                if (node.has("error")) {
                    throw new IllegalStateException(node.get("error").toString());
                }
                return node.path("result");
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("SSE 响应超时 id=" + id);
    }

    private synchronized void ensureStream() throws Exception {
        if (endpointUrl != null) {
            return;
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/event-stream");
        headers.forEach(rb::header);
        HttpResponse<InputStream> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("SSE 连接失败 HTTP " + resp.statusCode());
        }
        sseStream = resp.body();
        Thread t = new Thread(this::readSseLoop, "mcp-sse-" + url);
        t.setDaemon(true);
        t.start();
        sseReaderThread = t;
        String endpoint = endpointSignal.poll(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (endpoint == null) {
            throw new IllegalStateException("未收到 SSE endpoint 事件");
        }
        endpointUrl = resolveAgainstBase(endpoint);
    }

    private void readSseLoop() {
        String event = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(sseStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if ("endpoint".equals(event)) {
                        endpointSignal.offer(data, 3, TimeUnit.SECONDS);
                    } else if (!data.isEmpty()) {
                        dispatch(data);
                    }
                } else if (line.isEmpty()) {
                    event = null;
                }
            }
        } catch (Exception e) {
            log.debug("MCP SSE 读流结束: {}", e.getMessage());
        }
    }

    private void dispatch(String data) {
        try {
            JsonNode node = OM.readTree(data);
            sseData.add(data);
            if (node.has("id")) {
                responses.put(node.get("id").asInt(), node);
            }
        } catch (Exception ignore) {
            // 非 JSON 数据帧忽略
        }
    }

    private String resolveEndpoint() {
        if (endpointUrl == null) {
            throw new IllegalStateException("SSE endpoint 未就绪");
        }
        return endpointUrl;
    }

    private String resolveAgainstBase(String path) {
        if (path.startsWith("http")) {
            return path;
        }
        URI base = URI.create(url);
        String origin = base.getScheme() + "://" + base.getAuthority();
        return origin + (path.startsWith("/") ? path : "/" + path);
    }

    /** 关闭 SSE 长连接（探活/调用结束后可选调用） */
    public void close() {
        try {
            if (sseStream != null) {
                sseStream.close();
            }
            if (sseReaderThread != null) {
                sseReaderThread.interrupt();
            }
        } catch (Exception ignore) {
            // 关闭失败无需处理
        }
    }
}
