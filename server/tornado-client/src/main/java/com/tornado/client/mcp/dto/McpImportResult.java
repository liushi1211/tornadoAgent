package com.tornado.client.mcp.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** MCP 导入结果：成功导入的名称 + 被跳过的条目及原因 */
@Data
public class McpImportResult {
    private List<String> imported = new ArrayList<>();
    private List<Skipped> skipped = new ArrayList<>();

    @Data
    public static class Skipped {
        private final String name;
        private final String reason;
        public Skipped(String name, String reason) {
            this.name = name;
            this.reason = reason;
        }
    }
}
