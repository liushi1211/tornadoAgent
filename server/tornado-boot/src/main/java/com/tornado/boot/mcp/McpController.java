package com.tornado.boot.mcp;

import com.tornado.common.api.PageResult;
import com.tornado.common.api.Result;
import com.tornado.common.context.UserContext;
import com.tornado.common.entity.McpServerConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpServerService mcpServerService;

    @GetMapping
    public Result<PageResult<McpServerConfig>> page(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return Result.ok(mcpServerService.page(UserContext.userId(), page, size));
    }

    @PostMapping
    public Result<McpServerConfig> create(@Valid @RequestBody McpServerService.UpsertReq req) {
        return Result.ok(mcpServerService.create(UserContext.userId(), req));
    }

    @PutMapping("/{id}")
    public Result<McpServerConfig> update(@PathVariable Long id,
                                          @Valid @RequestBody McpServerService.UpsertReq req) {
        return Result.ok(mcpServerService.update(UserContext.userId(), id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mcpServerService.delete(UserContext.userId(), id);
        return Result.ok();
    }

    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id) {
        return Result.ok(mcpServerService.test(UserContext.userId(), id));
    }

    @PatchMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        mcpServerService.toggle(UserContext.userId(), id, enabled);
        return Result.ok();
    }
}
