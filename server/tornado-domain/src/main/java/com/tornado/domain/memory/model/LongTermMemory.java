package com.tornado.domain.memory.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 长期记忆聚合根（纯领域对象，跨会话共享）。category：preference|fact|summary。
 * status：1 有效、2 已合并淘汰。注入排序的相关性打分（类别权重 + 关键词命中 + 时效）封装于此。
 */
@Data
public class LongTermMemory {

    public static final int ST_ACTIVE = 1;
    public static final int ST_MERGED = 2;
    public static final int MAX_CONTENT = 500;

    private Long id;
    private Long userId;
    private String category;
    private String content;
    private Long sourceSessionId;
    private Integer hitCount;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isActive() {
        return status != null && status == ST_ACTIVE;
    }

    /** 同一条记忆是否“覆盖”候选内容：互相包含视为同一事实（用于 upsert 去重） */
    public boolean subsumes(String candidate) {
        if (content == null || candidate == null) {
            return false;
        }
        return content.contains(candidate) || candidate.contains(content);
    }

    /**
     * 注入相关性打分：类别基础权重（preference 3 / fact 2 / 其它 1）+ 每个命中词 +2 + 时效 0~1。
     * 返回 ≤0 视为不相关（上层过滤）。
     */
    public double relevance(String queryLower, long maxId) {
        double s = switch (category == null ? "" : category) {
            case "preference" -> 3.0;
            case "fact" -> 2.0;
            default -> 1.0;
        };
        String c = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (queryLower != null && !queryLower.isBlank()) {
            for (String token : queryLower.split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= 2 && c.contains(token)) {
                    s += 2.0;
                }
            }
        }
        if (id != null) {
            s += (double) id / Math.max(1, maxId);
        }
        return s;
    }
}
