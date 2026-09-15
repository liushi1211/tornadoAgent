package com.tornado.boot.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tornado.common.entity.LongTermMemory;
import com.tornado.common.mapper.LongTermMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 长期记忆：查看/删除/注入 TopK（category 权重 + 关键词命中 + 时效的轻量打分） */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryService {

    private final LongTermMemoryMapper memoryMapper;
    private final MemoryConfig memoryConfig;

    public List<LongTermMemory> list(String category) {
        LambdaQueryWrapper<LongTermMemory> q = new LambdaQueryWrapper<LongTermMemory>()
                .eq(LongTermMemory::getUserId, currentUid())
                .eq(LongTermMemory::getStatus, 1)
                .orderByDesc(LongTermMemory::getId)
                .last("LIMIT 200");
        if (category != null && !category.isBlank()) {
            q.eq(LongTermMemory::getCategory, category);
        }
        return memoryMapper.selectList(q);
    }

    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        memoryMapper.delete(new LambdaQueryWrapper<LongTermMemory>()
                .eq(LongTermMemory::getUserId, currentUid())
                .in(LongTermMemory::getId, ids));
    }

    /** 拼 systemPrompt 用：取最近 50 条打分截 topK，返回可注入文本（可为空串） */
    public String injectTopK(Long uid, String query, int topK) {
        List<LongTermMemory> recent = memoryMapper.selectList(new LambdaQueryWrapper<LongTermMemory>()
                .eq(LongTermMemory::getUserId, uid)
                .eq(LongTermMemory::getStatus, 1)
                .orderByDesc(LongTermMemory::getId)
                .last("LIMIT 50"));
        if (recent.isEmpty()) {
            return "";
        }
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        long maxId = recent.get(0).getId();
        List<LongTermMemory> top = recent.stream()
                .sorted(Comparator.comparingDouble((LongTermMemory m) -> score(m, q, maxId)).reversed())
                .limit(topK)
                .filter(m -> score(m, q, maxId) > 0)
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
            // 命中计数尽力而为，失败忽略
            try {
                memoryMapper.update(null, new LambdaUpdateWrapper<LongTermMemory>()
                        .eq(LongTermMemory::getId, m.getId())
                        .setSql("hit_count = hit_count + 1"));
            } catch (Exception ignore) {
                log.debug("hit_count 更新失败 id={}", m.getId());
            }
        }
        return sb.toString();
    }

    /** 简单 upsert 去重：同 category 下互相包含视为同一记忆 → 合并（update） */
    public void upsert(Long uid, String category, String content, Long sessionId) {
        if (content == null || content.isBlank()) {
            return;
        }
        String c = content.trim();
        if (c.length() > 500) {
            c = c.substring(0, 500);
        }
        List<LongTermMemory> sameCat = memoryMapper.selectList(new LambdaQueryWrapper<LongTermMemory>()
                .eq(LongTermMemory::getUserId, uid)
                .eq(LongTermMemory::getStatus, 1)
                .eq(LongTermMemory::getCategory, category));
        for (LongTermMemory old : sameCat) {
            String oc = old.getContent();
            if (oc.contains(c) || c.contains(oc)) {
                if (c.length() > oc.length()) {
                    old.setContent(c);
                    memoryMapper.updateById(old);
                }
                return;
            }
        }
        LongTermMemory m = new LongTermMemory();
        m.setUserId(uid);
        m.setCategory(category);
        m.setContent(c);
        m.setSourceSessionId(sessionId);
        m.setHitCount(0);
        m.setStatus(1);
        memoryMapper.insert(m);
    }

    private double score(LongTermMemory m, String queryLower, long maxId) {
        double s = switch (m.getCategory() == null ? "" : m.getCategory()) {
            case "preference" -> 3.0;
            case "fact" -> 2.0;
            default -> 1.0;
        };
        String c = m.getContent() == null ? "" : m.getContent().toLowerCase(Locale.ROOT);
        for (String token : queryLower.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && c.contains(token)) {
                s += 2.0;
            }
        }
        // recency：越新越高，权重最多 +1
        s += (double) m.getId() / Math.max(1, maxId);
        return s;
    }

    private Long currentUid() {
        return com.tornado.common.context.UserContext.userId();
    }

    public int injectTopKDefault() {
        return memoryConfig.getInjectTopk();
    }
}
