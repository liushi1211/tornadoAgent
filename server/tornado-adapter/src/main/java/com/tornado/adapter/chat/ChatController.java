package com.tornado.adapter.chat;

import com.tornado.app.chat.ChatStreamCmdExe;
import com.tornado.client.api.Result;
import com.tornado.client.chat.cmd.ChatCmd;
import com.tornado.client.chat.cmd.HitlResumeCmd;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatStreamCmdExe chatStreamCmdExe;

    @GetMapping("/models")
    public Result<Map<String, Object>> models() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("models", chatStreamCmdExe.models());
        data.put("default", chatStreamCmdExe.defaultModel());
        return Result.ok(data);
    }

    /** MVC 栈 StringHttpMessageConverter 默认 ISO-8859-1，produces 必须显式 charset=UTF-8 防中文乱码 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatCmd cmd) {
        return chatStreamCmdExe.stream(cmd);
    }

    /** 停止本轮生成（幂等，可重试） */
    @PostMapping("/stop/{streamId}")
    public Result<Void> stop(@PathVariable String streamId) {
        chatStreamCmdExe.stop(streamId);
        return Result.ok();
    }

    /** HITL 决策回传，SSE 续流（同一事件协议） */
    @PostMapping(value = "/hitl/{threadId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<ServerSentEvent<String>> resume(@PathVariable String threadId,
                                                @RequestBody HitlResumeCmd cmd) {
        return chatStreamCmdExe.resume(threadId, cmd);
    }
}
