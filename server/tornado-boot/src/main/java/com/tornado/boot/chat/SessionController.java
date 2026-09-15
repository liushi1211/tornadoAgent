package com.tornado.boot.chat;

import com.tornado.common.api.Result;
import com.tornado.common.context.UserContext;
import com.tornado.common.entity.ChatMessage;
import com.tornado.common.entity.ChatSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService sessionService;

    @GetMapping
    public Result<List<ChatSession>> list() {
        return Result.ok(sessionService.list(UserContext.userId()));
    }

    @PostMapping
    public Result<ChatSession> create(@RequestBody ChatSessionService.CreateReq req) {
        return Result.ok(sessionService.create(UserContext.userId(), req));
    }

    @PatchMapping("/{id}")
    public Result<ChatSession> patch(@PathVariable Long id, @RequestBody ChatSessionService.PatchReq req) {
        return Result.ok(sessionService.patch(UserContext.userId(), id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sessionService.delete(UserContext.userId(), id);
        return Result.ok();
    }

    @GetMapping("/{id}/messages")
    public Result<List<ChatMessage>> messages(@PathVariable Long id,
                                              @RequestParam(required = false) Long cursor,
                                              @RequestParam(defaultValue = "50") int size) {
        return Result.ok(sessionService.messages(UserContext.userId(), id, cursor, size));
    }
}
