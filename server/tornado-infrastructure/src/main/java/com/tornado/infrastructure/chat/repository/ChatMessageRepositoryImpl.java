package com.tornado.infrastructure.chat.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.domain.chat.model.ChatMessage;
import com.tornado.domain.chat.repository.ChatMessageRepository;
import com.tornado.infrastructure.chat.dataobject.ChatMessageDO;
import com.tornado.infrastructure.chat.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 消息仓储实现：DO⇄领域对象转换 + 封装 MyBatis-Plus（手动 LIMIT + cursor 倒序） */
@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryImpl implements ChatMessageRepository {

    private final ChatMessageMapper messageMapper;

    @Override
    public ChatMessage save(ChatMessage m) {
        ChatMessageDO d = toDO(m);
        messageMapper.insert(d);
        m.setId(d.getId());
        m.setCreatedAt(d.getCreatedAt());
        return m;
    }

    @Override
    public List<ChatMessage> listBeforeCursor(Long sessionId, Long uid, Long cursor, int size) {
        LambdaQueryWrapper<ChatMessageDO> q = new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getSessionId, sessionId)
                .eq(ChatMessageDO::getUserId, uid)
                .orderByDesc(ChatMessageDO::getId)
                .last("LIMIT " + Math.min(Math.max(size, 1), 100));
        if (cursor != null && cursor > 0) {
            q.lt(ChatMessageDO::getId, cursor);
        }
        return messageMapper.selectList(q).stream().map(this::toDomain).toList();
    }

    @Override
    public List<ChatMessage> listBySessionRolesAsc(Long sessionId, List<String> roles) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getSessionId, sessionId)
                        .in(ChatMessageDO::getRole, roles)
                        .orderByAsc(ChatMessageDO::getId))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<ChatMessage> listForIndex(long offset, int limit) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessageDO>()
                        .orderByAsc(ChatMessageDO::getId)
                        .last("LIMIT " + limit + " OFFSET " + offset))
                .stream().map(this::toDomain).toList();
    }

    private ChatMessageDO toDO(ChatMessage m) {
        ChatMessageDO d = new ChatMessageDO();
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

    private ChatMessage toDomain(ChatMessageDO d) {
        if (d == null) {
            return null;
        }
        ChatMessage m = new ChatMessage();
        m.setId(d.getId());
        m.setSessionId(d.getSessionId());
        m.setUserId(d.getUserId());
        m.setRole(d.getRole());
        m.setContent(d.getContent());
        m.setThinking(d.getThinking());
        m.setToolCalls(d.getToolCalls());
        m.setHitlId(d.getHitlId());
        m.setPromptTokens(d.getPromptTokens());
        m.setCompletionTokens(d.getCompletionTokens());
        m.setFinishReason(d.getFinishReason());
        m.setCreatedAt(d.getCreatedAt());
        return m;
    }
}
