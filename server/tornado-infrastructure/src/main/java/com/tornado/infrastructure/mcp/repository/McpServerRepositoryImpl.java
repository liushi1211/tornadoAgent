package com.tornado.infrastructure.mcp.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.client.api.PageResult;
import com.tornado.infrastructure.common.crypto.CryptoService;
import com.tornado.domain.mcp.model.McpServer;
import com.tornado.domain.mcp.repository.McpServerRepository;
import com.tornado.infrastructure.mcp.dataobject.McpServerDO;
import com.tornado.infrastructure.mcp.mapper.McpServerConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** MCP 配置仓储实现：DO⇄领域转换 + headers AES-GCM 加解密 */
@Repository
@RequiredArgsConstructor
public class McpServerRepositoryImpl implements McpServerRepository {

    private final McpServerConfigMapper mapper;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean existsName(Long uid, String name, Long excludeId) {
        LambdaQueryWrapper<McpServerDO> q = new LambdaQueryWrapper<McpServerDO>()
                .eq(McpServerDO::getUserId, uid).eq(McpServerDO::getName, name).eq(McpServerDO::getDeleted, 0);
        if (excludeId != null) {
            q.ne(McpServerDO::getId, excludeId);
        }
        return mapper.selectCount(q) > 0;
    }

    @Override
    public McpServer save(McpServer server) {
        McpServerDO d = new McpServerDO();
        d.setUserId(server.getUserId());
        d.setName(server.getName());
        d.setTransport(server.getTransport());
        d.setUrl(server.getUrl());
        d.setCommand(server.getCommand());
        d.setArgsJson(server.getArgsJson());
        d.setHeadersCipher(encryptHeaders(server.getPlainHeaders()));
        d.setEnabled(server.getEnabled());
        d.setHealthStatus(server.getHealthStatus());
        d.setDeleted(0);
        mapper.insert(d);
        server.setId(d.getId());
        server.setHeadersCipher(d.getHeadersCipher());
        return server;
    }

    @Override
    public void update(McpServer server) {
        McpServerDO d = mapper.selectById(server.getId());
        if (d == null) {
            return;
        }
        d.setName(server.getName());
        d.setTransport(server.getTransport());
        d.setUrl(server.getUrl());
        d.setCommand(server.getCommand());
        d.setArgsJson(server.getArgsJson());
        if (server.getPlainHeaders() != null) {
            d.setHeadersCipher(encryptHeaders(server.getPlainHeaders()));
        }
        d.setEnabled(server.getEnabled());
        d.setHealthStatus(server.getHealthStatus());
        d.setToolCacheJson(server.getToolCacheJson());
        d.setLastProbeAt(server.getLastProbeAt());
        mapper.updateById(d);
    }

    @Override
    public McpServer findById(Long id) {
        return toDomain(mapper.selectById(id));
    }

    @Override
    public PageResult<McpServer> page(Long uid, int page, int size) {
        long total = mapper.selectCount(new LambdaQueryWrapper<McpServerDO>()
                .eq(McpServerDO::getUserId, uid).eq(McpServerDO::getDeleted, 0));
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) (Math.max(page, 1) - 1) * safeSize;
        List<McpServer> records = mapper.selectList(new LambdaQueryWrapper<McpServerDO>()
                        .eq(McpServerDO::getUserId, uid).eq(McpServerDO::getDeleted, 0)
                        .orderByDesc(McpServerDO::getUpdatedAt)
                        .last("LIMIT " + safeSize + " OFFSET " + offset))
                .stream().map(this::toDomain).toList();
        return PageResult.of(total, page, safeSize, records);
    }

    @Override
    public List<McpServer> listActiveHealthy(Long uid) {
        return mapper.selectList(new LambdaQueryWrapper<McpServerDO>()
                        .eq(McpServerDO::getUserId, uid).eq(McpServerDO::getEnabled, 1)
                        .eq(McpServerDO::getHealthStatus, "HEALTHY").eq(McpServerDO::getDeleted, 0))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void deleteById(Long id) {
        mapper.deleteById(id);
    }

    private byte[] encryptHeaders(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        if (headers.isEmpty()) {
            return null;
        }
        try {
            return cryptoService.encrypt(objectMapper.writeValueAsString(headers).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("headers 加密失败", e);
        }
    }

    private McpServer toDomain(McpServerDO d) {
        if (d == null) {
            return null;
        }
        McpServer s = new McpServer();
        s.setId(d.getId());
        s.setUserId(d.getUserId());
        s.setName(d.getName());
        s.setTransport(d.getTransport());
        s.setUrl(d.getUrl());
        s.setCommand(d.getCommand());
        s.setArgsJson(d.getArgsJson());
        s.setHeadersCipher(d.getHeadersCipher());
        s.setEnabled(d.getEnabled());
        s.setHealthStatus(d.getHealthStatus());
        s.setToolCacheJson(d.getToolCacheJson());
        s.setLastProbeAt(d.getLastProbeAt());
        s.setCreatedAt(d.getCreatedAt());
        s.setUpdatedAt(d.getUpdatedAt());
        return s;
    }
}
