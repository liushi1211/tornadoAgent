package com.tornado.boot.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.common.api.PageResult;
import com.tornado.common.crypto.CryptoService;
import com.tornado.common.entity.McpServerConfig;
import com.tornado.common.ex.BizException;
import com.tornado.common.ex.ErrorCode;
import com.tornado.common.mapper.McpServerConfigMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** MCP Server 配置管理 + 探活（initialize/tools/list） */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerService {

    @Data
    public static class UpsertReq {
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

    private final McpServerConfigMapper mcpMapper;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PageResult<McpServerConfig> page(Long uid, int page, int size) {
        long total = mcpMapper.selectCount(new LambdaQueryWrapper<McpServerConfig>()
                .eq(McpServerConfig::getUserId, uid).eq(McpServerConfig::getDeleted, 0));
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) (Math.max(page, 1) - 1) * safeSize;
        List<McpServerConfig> records = mcpMapper.selectList(new LambdaQueryWrapper<McpServerConfig>()
                .eq(McpServerConfig::getUserId, uid)
                .eq(McpServerConfig::getDeleted, 0)
                .orderByDesc(McpServerConfig::getUpdatedAt)
                .last("LIMIT " + safeSize + " OFFSET " + offset));
        records.forEach(this::redact);
        return PageResult.of(total, page, safeSize, records);
    }

    public McpServerConfig create(Long uid, UpsertReq req) {
        ensureUniqueName(uid, req.getName(), null);
        McpServerConfig c = new McpServerConfig();
        c.setUserId(uid);
        apply(c, req);
        c.setEnabled(req.getEnabled() == null ? 0 : req.getEnabled());
        c.setHealthStatus("UNKNOWN");
        c.setDeleted(0);
        mcpMapper.insert(c);
        return redact(c);
    }

    public McpServerConfig update(Long uid, Long id, UpsertReq req) {
        McpServerConfig c = require(uid, id);
        if (!c.getName().equals(req.getName())) {
            ensureUniqueName(uid, req.getName(), id);
        }
        apply(c, req);
        if (req.getEnabled() != null) {
            c.setEnabled(req.getEnabled());
        }
        c.setHealthStatus("UNKNOWN");
        mcpMapper.updateById(c);
        return redact(c);
    }

    /** 探活：成功→HEALTHY+工具快照；失败→DOWN+抛 A-MCP-0001 */
    public Map<String, Object> test(Long uid, Long id) {
        McpServerConfig c = require(uid, id);
        McpJsonRpcClient client = buildClient(c);
        try {
            List<McpJsonRpcClient.McpTool> tools = client.probe();
            c.setHealthStatus("HEALTHY");
            List<Map<String, Object>> snapshot = new java.util.ArrayList<>();
            for (McpJsonRpcClient.McpTool t : tools) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", t.name());
                item.put("description", t.description() == null ? "" : t.description());
                item.put("inputSchema", objectMapper.readValue(
                        t.inputSchema().isMissingNode() || t.inputSchema().isNull()
                                ? "{\"type\":\"object\",\"properties\":{}}" : t.inputSchema().toString(),
                        Map.class));
                snapshot.add(item);
            }
            c.setToolCacheJson(objectMapper.writeValueAsString(snapshot));
            c.setLastProbeAt(LocalDateTime.now());
            mcpMapper.updateById(c);
            return Map.of("status", "HEALTHY",
                    "tools", tools.stream().map(McpJsonRpcClient.McpTool::name).toList());
        } catch (Exception e) {
            log.warn("MCP 探活失败 id={} url={}: {}", id, c.getUrl(), e.getMessage());
            c.setHealthStatus("DOWN");
            c.setLastProbeAt(LocalDateTime.now());
            mcpMapper.updateById(c);
            throw new BizException(ErrorCode.MCP_HANDSHAKE_FAILED, "MCP 握手失败: " + e.getMessage());
        } finally {
            client.close();
        }
    }

    public void toggle(Long uid, Long id, boolean enabled) {
        McpServerConfig c = require(uid, id);
        c.setEnabled(enabled ? 1 : 0);
        mcpMapper.updateById(c);
    }

    public void delete(Long uid, Long id) {
        require(uid, id);
        mcpMapper.deleteById(id);
    }

    public List<McpServerConfig> activeHealthy(Long uid) {
        return mcpMapper.selectList(new LambdaQueryWrapper<McpServerConfig>()
                .eq(McpServerConfig::getUserId, uid)
                .eq(McpServerConfig::getEnabled, 1)
                .eq(McpServerConfig::getHealthStatus, "HEALTHY")
                .eq(McpServerConfig::getDeleted, 0));
    }

    public McpJsonRpcClient buildClient(McpServerConfig c) {
        return new McpJsonRpcClient(c.getTransport(), c.getUrl(), decryptHeaders(c));
    }

    public McpServerConfig require(Long uid, Long id) {
        McpServerConfig c = mcpMapper.selectById(id);
        if (c == null || !c.getUserId().equals(uid)) {
            throw new BizException(ErrorCode.NOT_FOUND, "MCP 配置不存在");
        }
        return c;
    }

    public Map<String, String> decryptHeaders(McpServerConfig c) {
        if (c.getHeadersCipher() == null || c.getHeadersCipher().length == 0) {
            return Map.of();
        }
        try {
            String json = cryptoService.decryptStr(c.getHeadersCipher());
            @SuppressWarnings("unchecked")
            Map<String, String> map = objectMapper.readValue(json, Map.class);
            return map;
        } catch (Exception e) {
            log.warn("MCP headers 解密失败 id={}", c.getId());
            return Map.of();
        }
    }

    private void apply(McpServerConfig c, UpsertReq req) {
        c.setName(req.getName());
        c.setTransport(req.getTransport());
        c.setUrl(req.getUrl());
        c.setCommand(req.getCommand());
        c.setArgsJson(req.getArgsJson());
        if (req.getHeaders() != null && !req.getHeaders().isEmpty()) {
            try {
                c.setHeadersCipher(cryptoService.encrypt(objectMapper.writeValueAsString(req.getHeaders())
                        .getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException("headers 加密失败", e);
            }
        } else if (req.getHeaders() != null) {
            c.setHeadersCipher(null);
        }
    }

    private void ensureUniqueName(Long uid, String name, Long excludeId) {
        LambdaQueryWrapper<McpServerConfig> q = new LambdaQueryWrapper<McpServerConfig>()
                .eq(McpServerConfig::getUserId, uid).eq(McpServerConfig::getName, name)
                .eq(McpServerConfig::getDeleted, 0);
        if (excludeId != null) {
            q.ne(McpServerConfig::getId, excludeId);
        }
        if (mcpMapper.selectCount(q) > 0) {
            throw new BizException(ErrorCode.INVALID_PARAM, "MCP 名称已存在: " + name);
        }
    }

    private McpServerConfig redact(McpServerConfig c) {
        // 密文不回前端
        c.setHeadersCipher(null);
        return c;
    }
}
