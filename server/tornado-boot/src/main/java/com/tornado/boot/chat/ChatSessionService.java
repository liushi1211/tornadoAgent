package com.tornado.boot.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.boot.memory.MemorySummarizeService;
import com.tornado.common.entity.ChatMessage;
import com.tornado.common.entity.ChatSession;
import com.tornado.common.ex.BizException;
import com.tornado.common.ex.ErrorCode;
import com.tornado.common.mapper.ChatMessageMapper;
import com.tornado.common.mapper.ChatSessionMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 会话管理（所有操作强制 userId 条件） */
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    @Data
    public static class CreateReq {
        private String modelId;
        private String title;
    }

    @Data
    public static class PatchReq {
        private String title;
        /** API 层用布尔语义（true/false），入库转 tinyint；前端传 1/0 会 400 */
        private Boolean pinned;
        private Boolean archived;
    }

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final com.tornado.boot.chat.model.ModelConfigHolder modelHolder;
    private final MemorySummarizeService summarizeService;

    public List<ChatSession> list(Long uid) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, uid)
                .orderByDesc(ChatSession::getPinned)
                .orderByDesc(ChatSession::getUpdatedAt));
    }

    public ChatSession create(Long uid, CreateReq req) {
        ChatSession s = new ChatSession();
        s.setUserId(uid);
        s.setTitle(req.getTitle() == null || req.getTitle().isBlank() ? "新会话" : req.getTitle());
        s.setModelId(modelHolder.findOrDefault(req.getModelId()).getId());
        s.setPinned(0);
        s.setArchived(0);
        sessionMapper.insert(s);
        return s;
    }

    public ChatSession patch(Long uid, Long id, PatchReq req) {
        ChatSession s = requireOwned(uid, id);
        boolean archivedNow = false;
        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            s.setTitle(req.getTitle());
        }
        if (req.getPinned() != null) {
            s.setPinned(Boolean.TRUE.equals(req.getPinned()) ? 1 : 0);
        }
        if (req.getArchived() != null) {
            boolean toArchived = Boolean.TRUE.equals(req.getArchived());
            if (toArchived && (s.getArchived() == null || s.getArchived() == 0)) {
                archivedNow = true;
            }
            s.setArchived(toArchived ? 1 : 0);
        }
        sessionMapper.updateById(s);
        if (archivedNow) {
            // 归档触发长期记忆沉淀（异步）
            summarizeService.summarizeSession(s.getId());
        }
        return s;
    }

    public void delete(Long uid, Long id) {
        requireOwned(uid, id);
        sessionMapper.deleteById(id);
    }

    /** cursor 倒序分页：返回比 cursor 小的最新 size 条（升序输出便于前端回放） */
    public List<ChatMessage> messages(Long uid, Long id, Long cursor, int size) {
        requireOwned(uid, id);
        LambdaQueryWrapper<ChatMessage> q = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, id)
                .eq(ChatMessage::getUserId, uid)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + Math.min(Math.max(size, 1), 100));
        if (cursor != null && cursor > 0) {
            q.lt(ChatMessage::getId, cursor);
        }
        List<ChatMessage> list = messageMapper.selectList(q);
        List<ChatMessage> asc = new java.util.ArrayList<>(list);
        java.util.Collections.reverse(asc);
        return asc;
    }

    public ChatSession requireOwned(Long uid, Long id) {
        ChatSession s = sessionMapper.selectById(id);
        if (s == null || !s.getUserId().equals(uid)) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    /** 流式入口用：sessionId 为空自动建会话；否则校验归属 */
    public ChatSession resolveForChat(Long uid, Long sessionId, String modelId, String content) {
        if (sessionId == null) {
            CreateReq req = new CreateReq();
            req.setModelId(modelId);
            req.setTitle(content.length() > 20 ? content.substring(0, 20) : content);
            return create(uid, req);
        }
        ChatSession s = requireOwned(uid, sessionId);
        if (modelId != null && !modelId.isBlank() && !modelId.equals(s.getModelId())) {
            s.setModelId(modelHolder.findOrDefault(modelId).getId());
            sessionMapper.updateById(s);
        }
        return s;
    }
}
