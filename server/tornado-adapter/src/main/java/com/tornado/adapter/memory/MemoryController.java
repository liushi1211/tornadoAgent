package com.tornado.adapter.memory;

import com.tornado.app.memory.LongTermMemoryService;
import com.tornado.client.api.Result;
import com.tornado.client.memory.dto.MemoryDTO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/memories")
@RequiredArgsConstructor
public class MemoryController {

    private final LongTermMemoryService longTermMemoryService;

    @Data
    public static class DeleteReq {
        private List<Long> ids;
    }

    @GetMapping("/long-term")
    public Result<List<MemoryDTO>> list(@RequestParam(required = false) String category) {
        return Result.ok(longTermMemoryService.list(category));
    }

    @DeleteMapping("/long-term")
    public Result<Void> delete(@RequestBody DeleteReq req) {
        longTermMemoryService.delete(req.getIds());
        return Result.ok();
    }
}
