package com.tornado.app.memory;

import com.tornado.client.context.UserContext;
import com.tornado.client.memory.dto.MemoryDTO;
import com.tornado.domain.memory.gateway.MemorySettingsGateway;
import com.tornado.domain.memory.model.LongTermMemory;
import com.tornado.domain.memory.repository.LongTermMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 长期记忆应用服务：查看/删除/注入 TopK/upsert 去重/兜底判重计数。
 * 相关性打分（类别权重 + 关键词命中 + 时效）封装在 domain LongTermMemory.relevance，持久化经仓储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryService {

    private final LongTermMemoryRepository memoryRepository;
    private final MemorySettingsGateway memorySettings;

    public List<MemoryDTO> list(String category) {
        return memoryRepository.listActive(currentUid(), category, 200).stream().map(this::toDTO).toList();
    }

    public void delete(List<Long> ids) {
        memoryRepository.deleteByIds(currentUid(), ids);
    }

    /** 拼 systemPrompt 用：取最近 50 条打分截 topK，返回可注入文本（可为空串） */
    public String injectTopK(Long uid, String query, int topK) {
        List<LongTermMemory> recent = memoryRepository.findRecentActive(uid, 50);
        if (recent.isEmpty()) {
            return "";
        }
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        long maxId = recent.get(0).getId();
        List<LongTermMemory> top = recent.stream()
                .sorted(Comparator.comparingDouble((LongTermMemory m) -> m.relevance(q, maxId)).reversed())
                .limit(topK)
                .filter(m -> m.relevance(q, maxId) > 0)
                .toList();
        if (top.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n【关于用户的长期记忆（供参考，勿直接复述）】\n");
        int budget = 0;
        for (LongTermMemory m : top) {
            if (budget > 800) {
                break;
            }
            sb.append("- [").append(m.getCategory()).append("] ").append(m.getContent()).append('\n');
            budget += m.getContent().length();
            try {
                memoryRepository.touchHitCount(m.getId());
            } catch (Exception ignore) {
                log.debug("hit_count 更新失败 id={}", m.getId());
            }
        }
        return sb.toString();
    }

    public int injectTopKDefault() {
        return memorySettings.injectTopk();
    }

    /** 简单 upsert 去重：同 category 下互相包含视为同一记忆 → 合并（update） */
    public void upsert(Long uid, String category, String content, Long sessionId) {
        if (content == null || content.isBlank()) {
            return;
        }
        String c = content.trim();
        if (c.length() > LongTermMemory.MAX_CONTENT) {
            c = c.substring(0, LongTermMemory.MAX_CONTENT);
        }
        final String value = c;
        for (LongTermMemory old : memoryRepository.findActiveByCategory(uid, category)) {
            if (old.subsumes(value)) {
                if (value.length() > (old.getContent() == null ? 0 : old.getContent().length())) {
                    old.setContent(value);
                    memoryRepository.updateContent(old);
                }
                return;
            }
        }
        LongTermMemory m = new LongTermMemory();
        m.setUserId(uid);
        m.setCategory(category);
        m.setContent(value);
        m.setSourceSessionId(sessionId);
        m.setHitCount(0);
        m.setStatus(LongTermMemory.ST_ACTIVE);
        memoryRepository.save(m);
    }

    /** 兜底任务判重：该来源会话是否已沉淀过记忆 */
    public long countBySourceSession(Long sessionId) {
        return memoryRepository.countBySourceSession(sessionId);
    }

    private MemoryDTO toDTO(LongTermMemory m) {
        MemoryDTO d = new MemoryDTO();
        d.setId(m.getId());
        d.setCategory(m.getCategory());
        d.setContent(m.getContent());
        d.setSourceSessionId(m.getSourceSessionId());
        d.setHitCount(m.getHitCount());
        d.setStatus(m.getStatus());
        d.setCreatedAt(m.getCreatedAt());
        d.setUpdatedAt(m.getUpdatedAt());
        return d;
    }

    private Long currentUid() {
        return UserContext.userId();
    }
}
