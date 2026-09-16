package com.tornado.adapter.mcp;

import com.tornado.app.mcp.McpService;
import com.tornado.client.api.PageResult;
import com.tornado.client.api.Result;
import com.tornado.client.context.UserContext;
import com.tornado.client.mcp.cmd.McpUpsertCmd;
import com.tornado.client.mcp.dto.McpDTO;
import com.tornado.client.mcp.dto.McpTestResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpService mcpService;

    @GetMapping
    public Result<PageResult<McpDTO>> page(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return Result.ok(mcpService.page(UserContext.userId(), page, size));
    }

    @PostMapping
    public Result<McpDTO> create(@Valid @RequestBody McpUpsertCmd cmd) {
        return Result.ok(mcpService.create(UserContext.userId(), cmd));
    }

    @PutMapping("/{id}")
    public Result<McpDTO> update(@PathVariable Long id, @Valid @RequestBody McpUpsertCmd cmd) {
        return Result.ok(mcpService.update(UserContext.userId(), id, cmd));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mcpService.delete(UserContext.userId(), id);
        return Result.ok();
    }

    @PostMapping("/{id}/test")
    public Result<McpTestResult> test(@PathVariable Long id) {
        return Result.ok(mcpService.test(UserContext.userId(), id));
    }

    @PatchMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        mcpService.toggle(UserContext.userId(), id, enabled);
        return Result.ok();
    }
}
