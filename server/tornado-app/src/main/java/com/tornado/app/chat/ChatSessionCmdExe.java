package com.tornado.app.chat;

import com.tornado.app.memory.MemorySummarizeService;
import com.tornado.client.chat.cmd.SessionCreateCmd;
import com.tornado.client.chat.cmd.SessionPatchCmd;
import com.tornado.client.chat.dto.ChatMessageDTO;
import com.tornado.client.chat.dto.ChatSessionDTO;
import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.domain.chat.model.ChatMessage;
import com.tornado.domain.chat.model.ChatSession;
import com.tornado.domain.chat.repository.ChatMessageRepository;
import com.tornado.domain.chat.repository.ChatSessionRepository;
import com.tornado.domain.modelconfig.ModelCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话应用服务（管理面 + 流式入口辅助）：列表/新建/改/删/历史，所有操作按 userId 隔离。
 * 归档触发长期记忆沉淀（异步）。返回 client DTO，domain 模型不外泄到 adapter。
 */
@Service
@RequiredArgsConstructor
public class ChatSessionCmdExe {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ModelCatalog modelCatalog;
    private final MemorySummarizeService summarizeService;

    public List<ChatSessionDTO> list(Long uid) {
        return sessionRepository.listByUser(uid).stream().map(this::toDTO).toList();
    }

    public ChatSessionDTO create(Long uid, SessionCreateCmd cmd) {
        ChatSession s = newSession(uid, cmd.getModelId(), cmd.getTitle());
        sessionRepository.save(s);
        return toDTO(s);
    }

    private ChatSession newSession(Long uid, String modelId, String title) {
        ChatSession s = new ChatSession();
        s.setUserId(uid);
        s.setTitle(title == null || title.isBlank() ? ChatSession.DEFAULT_TITLE : title);
        s.setModelId(modelCatalog.findOrDefault(modelId).getId());
        s.setPinned(0);
        s.setArchived(0);
        return s;
    }

    public ChatSessionDTO patch(Long uid, Long id, SessionPatchCmd cmd) {
        ChatSession s = requireOwned(uid, id);
        boolean archivedNow = false;
        if (cmd.getTitle() != null && !cmd.getTitle().isBlank()) {
            s.setTitle(cmd.getTitle());
        }
        if (cmd.getPinned() != null) {
            s.setPinned(Boolean.TRUE.equals(cmd.getPinned()) ? 1 : 0);
        }
        if (cmd.getArchived() != null) {
            boolean toArchived = Boolean.TRUE.equals(cmd.getArchived());
            if (toArchived && (s.getArchived() == null || s.getArchived() == 0)) {
                archivedNow = true;
            }
            s.setArchived(toArchived ? 1 : 0);
        }
        sessionRepository.update(s);
        if (archivedNow) {
            // 归档触发长期记忆沉淀（异步）
            summarizeService.summarizeSession(s.getId());
        }
        return toDTO(s);
    }

    public void delete(Long uid, Long id) {
        requireOwned(uid, id);
        sessionRepository.deleteById(id);
    }

    /** cursor 倒序分页：返回比 cursor 小的最新 size 条（升序输出便于前端回放） */
    public List<ChatMessageDTO> messages(Long uid, Long id, Long cursor, int size) {
        requireOwned(uid, id);
        List<ChatMessage> desc = messageRepository.listBeforeCursor(id, uid, cursor, size);
        List<ChatMessage> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        return asc.stream().map(this::toDTO).toList();
    }

    public ChatSession requireOwned(Long uid, Long id) {
        ChatSession s = sessionRepository.findById(id);
        if (s == null || !s.getUserId().equals(uid)) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    /** 流式入口用：sessionId 为空自动建会话；否则校验归属（模型粘性：显式换模型则落库） */
    public ChatSession resolveForChat(Long uid, Long sessionId, String modelId, String content) {
        if (sessionId == null) {
            ChatSession s = newSession(uid, modelId, content.length() > 20 ? content.substring(0, 20) : content);
            sessionRepository.save(s);
            return s;
        }
        ChatSession s = requireOwned(uid, sessionId);
        if (modelId != null && !modelId.isBlank() && !modelId.equals(s.getModelId())) {
            s.setModelId(modelCatalog.findOrDefault(modelId).getId());
            sessionRepository.update(s);
        }
        return s;
    }

    private ChatSessionDTO toDTO(ChatSession s) {
        ChatSessionDTO d = new ChatSessionDTO();
        d.setId(s.getId());
        d.setUserId(s.getUserId());
        d.setTitle(s.getTitle());
        d.setModelId(s.getModelId());
        d.setPinned(s.getPinned());
        d.setArchived(s.getArchived());
        d.setCreatedAt(s.getCreatedAt());
        d.setUpdatedAt(s.getUpdatedAt());
        return d;
    }

    private ChatMessageDTO toDTO(ChatMessage m) {
        ChatMessageDTO d = new ChatMessageDTO();
        d.setId(m.getId());
        d.setSessionId(m.getSessionId());
        d.setUserId(m.getUserId());
        d.setRole(m.getRole());
        d.setContent(m.getContent());
        d.setThinking(m.getThinking());
        d.setToolCalls(m.getToolCalls());
        d.setHitlId(m.getHitlId());
        d.setPromptTokens(m.getPromptTokens());
        d.setCompletionTokens(m.getCompletionTokens());
        d.setFinishReason(m.getFinishReason());
        d.setCreatedAt(m.getCreatedAt());
        return d;
    }
}
