package com.tornado.adapter.chat;

import com.tornado.app.chat.ChatSessionCmdExe;
import com.tornado.client.api.Result;
import com.tornado.client.chat.cmd.SessionCreateCmd;
import com.tornado.client.chat.cmd.SessionPatchCmd;
import com.tornado.client.chat.dto.ChatMessageDTO;
import com.tornado.client.chat.dto.ChatSessionDTO;
import com.tornado.client.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionCmdExe sessionCmdExe;

    @GetMapping
    public Result<List<ChatSessionDTO>> list() {
        return Result.ok(sessionCmdExe.list(UserContext.userId()));
    }

    @PostMapping
    public Result<ChatSessionDTO> create(@RequestBody SessionCreateCmd cmd) {
        return Result.ok(sessionCmdExe.create(UserContext.userId(), cmd));
    }

    @PatchMapping("/{id}")
    public Result<ChatSessionDTO> patch(@PathVariable Long id, @RequestBody SessionPatchCmd cmd) {
        return Result.ok(sessionCmdExe.patch(UserContext.userId(), id, cmd));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sessionCmdExe.delete(UserContext.userId(), id);
        return Result.ok();
    }

    @GetMapping("/{id}/messages")
    public Result<List<ChatMessageDTO>> messages(@PathVariable Long id,
                                                 @RequestParam(required = false) Long cursor,
                                                 @RequestParam(defaultValue = "50") int size) {
        return Result.ok(sessionCmdExe.messages(UserContext.userId(), id, cursor, size));
    }
}
