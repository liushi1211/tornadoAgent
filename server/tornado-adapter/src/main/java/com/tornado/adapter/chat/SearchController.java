package com.tornado.adapter.chat;

import com.tornado.app.chat.MessageSearchQryExe;
import com.tornado.client.api.PageResult;
import com.tornado.client.api.Result;
import com.tornado.client.chat.dto.MessageSearchHit;
import com.tornado.client.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final MessageSearchQryExe messageSearchQryExe;

    /** 消息全文检索（按当前用户隔离） */
    @GetMapping("/messages")
    public Result<PageResult<MessageSearchHit>> messages(@RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) Long sessionId,
                                                         @RequestParam(required = false) String role,
                                                         @RequestParam(required = false) String from,
                                                         @RequestParam(required = false) String to,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return Result.ok(messageSearchQryExe.search(UserContext.userId(), keyword, sessionId, role, from, to, page, size));
    }

    /** 历史消息一次性回填到 ES（canal 只推新变更；存量灌一次）。返回灌入条数。 */
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestParam(defaultValue = "500") int batchSize) {
        return Result.ok(messageSearchQryExe.backfill(batchSize));
    }
}
