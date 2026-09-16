package com.tornado.app.mcp;

import com.tornado.client.api.PageResult;
import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.client.mcp.cmd.McpUpsertCmd;
import com.tornado.client.mcp.dto.McpDTO;
import com.tornado.client.mcp.dto.McpTestResult;
import com.tornado.domain.mcp.gateway.McpProbeGateway;
import com.tornado.domain.mcp.model.McpServer;
import com.tornado.domain.mcp.repository.McpServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** MCP Server 配置应用服务：CRUD + 探活（委托 domain 网关）+ 启停 */
@Service
@RequiredArgsConstructor
public class McpService {

    private final McpServerRepository mcpServerRepository;
    private final McpProbeGateway mcpProbeGateway;

    public PageResult<McpDTO> page(Long uid, int page, int size) {
        PageResult<McpServer> p = mcpServerRepository.page(uid, page, size);
        PageResult<McpDTO> out = new PageResult<>();
        out.setTotal(p.getTotal());
        out.setPage(p.getPage());
        out.setSize(p.getSize());
        out.setRecords(p.getRecords().stream().map(this::toDTO).toList());
        return out;
    }

    public McpDTO create(Long uid, McpUpsertCmd cmd) {
        if (mcpServerRepository.existsName(uid, cmd.getName(), null)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "MCP 名称已存在: " + cmd.getName());
        }
        McpServer s = new McpServer();
        s.setUserId(uid);
        apply(s, cmd);
        s.setEnabled(cmd.getEnabled() == null ? 0 : cmd.getEnabled());
        s.setHealthStatus("UNKNOWN");
        mcpServerRepository.save(s);
        return toDTO(s);
    }

    public McpDTO update(Long uid, Long id, McpUpsertCmd cmd) {
        McpServer s = require(uid, id);
        if (!s.getName().equals(cmd.getName()) && mcpServerRepository.existsName(uid, cmd.getName(), id)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "MCP 名称已存在: " + cmd.getName());
        }
        apply(s, cmd);
        if (cmd.getEnabled() != null) {
            s.setEnabled(cmd.getEnabled());
        }
        s.setHealthStatus("UNKNOWN");
        mcpServerRepository.update(s);
        return toDTO(s);
    }

    /** 探活：成功→HEALTHY+快照；失败→DOWN 并抛 A-MCP-0001 */
    public McpTestResult test(Long uid, Long id) {
        McpServer s = require(uid, id);
        try {
            McpProbeGateway.ProbeResult r = mcpProbeGateway.probe(s);
            s.applyProbe(r.healthStatus(), r.toolCacheJson());
            mcpServerRepository.update(s);
            return new McpTestResult("HEALTHY", r.toolNames());
        } catch (Exception e) {
            s.applyProbe("DOWN", s.getToolCacheJson());
            mcpServerRepository.update(s);
            throw new BizException(ErrorCode.MCP_HANDSHAKE_FAILED, "MCP 握手失败: " + e.getMessage());
        }
    }

    public void toggle(Long uid, Long id, boolean enabled) {
        McpServer s = require(uid, id);
        s.setEnabledFlag(enabled);
        mcpServerRepository.update(s);
    }

    public void delete(Long uid, Long id) {
        require(uid, id);
        mcpServerRepository.deleteById(id);
    }

    private McpServer require(Long uid, Long id) {
        McpServer s = mcpServerRepository.findById(id);
        if (s == null || !s.getUserId().equals(uid)) {
            throw new BizException(ErrorCode.NOT_FOUND, "MCP 配置不存在");
        }
        return s;
    }

    private void apply(McpServer s, McpUpsertCmd cmd) {
        s.setName(cmd.getName());
        s.setTransport(cmd.getTransport());
        s.setUrl(cmd.getUrl());
        s.setCommand(cmd.getCommand());
        s.setArgsJson(cmd.getArgsJson());
        if (cmd.getHeaders() != null) {
            s.setPlainHeaders(cmd.getHeaders());
        }
    }

    private McpDTO toDTO(McpServer s) {
        McpDTO d = new McpDTO();
        d.setId(s.getId());
        d.setUserId(s.getUserId());
        d.setName(s.getName());
        d.setTransport(s.getTransport());
        d.setUrl(s.getUrl());
        d.setCommand(s.getCommand());
        d.setArgsJson(s.getArgsJson());
        d.setEnabled(s.getEnabled());
        d.setHealthStatus(s.getHealthStatus());
        d.setToolCacheJson(s.getToolCacheJson());
        d.setLastProbeAt(s.getLastProbeAt());
        d.setCreatedAt(s.getCreatedAt());
        d.setUpdatedAt(s.getUpdatedAt());
        return d;
    }
}
