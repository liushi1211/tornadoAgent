package com.tornado.domain.mcp.repository;

import com.tornado.client.api.PageResult;
import com.tornado.domain.mcp.model.McpServer;

import java.util.List;

/** MCP Server 配置仓储接口 */
public interface McpServerRepository {
    boolean existsName(Long uid, String name, Long excludeId);

    /** 新增（返回带 id）；若 plainHeaders 非空则 infra 加密为 headersCipher */
    McpServer save(McpServer server);

    /** 更新；plainHeaders 非空时重加密，显式传空 Map 表示清除 */
    void update(McpServer server);

    McpServer findById(Long id);

    PageResult<McpServer> page(Long uid, int page, int size);

    List<McpServer> listActiveHealthy(Long uid);

    void deleteById(Long id);
}
