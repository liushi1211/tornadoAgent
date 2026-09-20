package com.tornado.app.chat;

import com.tornado.client.api.PageResult;
import com.tornado.client.chat.dto.MessageSearchHit;
import com.tornado.domain.chat.gateway.ChatMessageIndexGateway;
import com.tornado.domain.chat.gateway.ChatMessageSearchGateway;
import com.tornado.domain.chat.model.ChatMessage;
import com.tornado.domain.chat.model.ChatMessageIndex;
import com.tornado.domain.chat.model.MessageSearchQuery;
import com.tornado.domain.chat.model.MessageSearchResult;
import com.tornado.domain.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 消息检索应用服务：ES 全文检索 + 历史一次性回填（canal 只推新变更，存量需灌一次）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageSearchQryExe {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChatMessageSearchGateway searchGateway;
    private final ChatMessageIndexGateway indexGateway;
    private final ChatMessageRepository messageRepository;

    public PageResult<MessageSearchHit> search(Long uid, String keyword, Long sessionId, String role,
                                               String from, String to, int page, int size) {
        MessageSearchResult res = searchGateway.search(
                new MessageSearchQuery(uid, keyword, sessionId, role, from, to, page, size));
        List<MessageSearchHit> hits = res.hits().stream().map(this::toHit).toList();
        return PageResult.of(res.total(), page, size, hits);
    }

    /** 把库里存量 chat_message 全量灌入 ES（幂等 upsert，按 id）。返回灌入条数。 */
    public int backfill(int batchSize) {
        int bs = Math.min(Math.max(batchSize, 100), 2000);
        int total = 0;
        long offset = 0;
        while (true) {
            List<ChatMessage> rows = messageRepository.listForIndex(offset, bs);
            if (rows.isEmpty()) {
                break;
            }
            indexGateway.bulkUpsert(rows.stream().map(this::toIndex).toList());
            total += rows.size();
            offset += rows.size();
            if (rows.size() < bs) {
                break;
            }
        }
        log.info("消息回填 ES 完成，共 {} 条", total);
        return total;
    }

    private ChatMessageIndex toIndex(ChatMessage m) {
        return new ChatMessageIndex(
                String.valueOf(m.getId()),
                m.getUserId(),
                m.getSessionId(),
                m.getRole(),
                m.getContent(),
                m.getThinking(),
                m.getToolCalls(),
                m.getFinishReason(),
                fmt(m.getCreatedAt()));
    }

    private MessageSearchHit toHit(ChatMessageIndex d) {
        MessageSearchHit h = new MessageSearchHit();
        h.setId(d.id());
        h.setUserId(d.userId());
        h.setSessionId(d.sessionId());
        h.setRole(d.role());
        h.setContent(d.content());
        h.setThinking(d.thinking());
        h.setToolCalls(d.toolCalls());
        h.setFinishReason(d.finishReason());
        h.setCreatedAt(d.createdAt());
        return h;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }
}
