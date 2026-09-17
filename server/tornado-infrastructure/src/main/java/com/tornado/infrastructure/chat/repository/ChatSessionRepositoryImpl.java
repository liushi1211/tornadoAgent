package com.tornado.infrastructure.chat.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.domain.chat.model.ChatSession;
import com.tornado.domain.chat.repository.ChatSessionRepository;
import com.tornado.infrastructure.chat.dataobject.ChatSessionDO;
import com.tornado.infrastructure.chat.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 会话仓储实现：DO⇄领域对象转换 + 封装 MyBatis-Plus（软删 @TableLogic） */
@Repository
@RequiredArgsConstructor
public class ChatSessionRepositoryImpl implements ChatSessionRepository {

    private final ChatSessionMapper sessionMapper;

    @Override
    public ChatSession save(ChatSession s) {
        ChatSessionDO d = toDO(s);
        d.setDeleted(0);
        sessionMapper.insert(d);
        s.setId(d.getId());
        s.setCreatedAt(d.getCreatedAt());
        s.setUpdatedAt(d.getUpdatedAt());
        return s;
    }

    @Override
    public void update(ChatSession s) {
        sessionMapper.updateById(toDO(s));
    }

    @Override
    public ChatSession findById(Long id) {
        return toDomain(sessionMapper.selectById(id));
    }

    @Override
    public List<ChatSession> listByUser(Long uid) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getUserId, uid)
                        .orderByDesc(ChatSessionDO::getPinned)
                        .orderByDesc(ChatSessionDO::getUpdatedAt))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void deleteById(Long id) {
        sessionMapper.deleteById(id);
    }

    @Override
    public List<ChatSession> findArchivedBefore(LocalDateTime cutoff) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getArchived, 1)
                        .lt(ChatSessionDO::getUpdatedAt, cutoff))
                .stream().map(this::toDomain).toList();
    }

    private ChatSessionDO toDO(ChatSession s) {
        ChatSessionDO d = new ChatSessionDO();
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

    private ChatSession toDomain(ChatSessionDO d) {
        if (d == null) {
            return null;
        }
        ChatSession s = new ChatSession();
        s.setId(d.getId());
        s.setUserId(d.getUserId());
        s.setTitle(d.getTitle());
        s.setModelId(d.getModelId());
        s.setPinned(d.getPinned());
        s.setArchived(d.getArchived());
        s.setCreatedAt(d.getCreatedAt());
        s.setUpdatedAt(d.getUpdatedAt());
        return s;
    }
}
