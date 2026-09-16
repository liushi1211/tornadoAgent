package com.tornado.boot.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.boot.chat.model.ChatModelFactory;
import com.tornado.common.entity.ChatMessage;
import com.tornado.common.entity.ChatSession;
import com.tornado.common.mapper.ChatMessageMapper;
import com.tornado.common.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆沉淀：会话归档（PATCH archived=1）时异步调 cheap 模型抽取 ≤5 条 {category,content}，
 * 简单去重后 upsert 入 long_term_memory。每日 03:00 兜底扫描归档超 7 天未沉淀的会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemorySummarizeService {

    private static final String EXTRACT_PROMPT = """
            你是记忆整理助手。请从以下对话中提炼最多 5 条值得长期记住的用户信息（偏好 preference / 事实 fact / 结论 summary），
            每条不超过 200 字。仅输出 JSON 数组，元素形如 {"category":"preference|fact|summary","content":"..."}，无其他文字。
            """;

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;

    private final LongTermMemoryService longTermMemoryService;
    private final ChatModelFactory chatModelFactory;
    private final MemoryConfig memoryConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async("ragIngestExecutor")
    public void summarizeSession(Long sessionId) {
        try {
            ChatSession session = sessionMapper.selectById(sessionId);
            if (session == null) {
                return;
            }
            List<ChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, sessionId)
                    .in(ChatMessage::getRole, "user", "assistant")
                    .orderByAsc(ChatMessage::getId));
            if (messages.isEmpty()) {
                return;
            }
            String conv = messages.stream()
                    .map(m -> m.getRole() + ": " + truncate(m.getContent(), 500))
                    .collect(Collectors.joining("\n"));
            conv = truncate(conv, 8000);
            ChatModel model = chatModelFactory.get(memoryConfig.getLongtermModel());
            String answer = model.call(new Prompt(List.of(
                    new SystemMessage(EXTRACT_PROMPT), new UserMessage(conv)))).getResult().getOutput().getText();
            JsonNode arr = parseJsonArray(answer);
            if (arr == null) {
                log.warn("会话 {} 摘要解析失败: {}", sessionId, truncate(answer, 200));
                return;
            }
            int n = 0;
            for (JsonNode item : arr) {
                if (n++ >= 5) {
                    break;
                }
                String category = item.path("category").asText("summary");
                if (!List.of("preference", "fact", "summary").contains(category)) {
                    category = "summary";
                }
                longTermMemoryService.upsert(session.getUserId(), category,
                        item.path("content").asText(""), sessionId);
            }
            log.info("会话 {} 长期记忆沉淀完成，共 {} 条", sessionId, n);
        } catch (Exception e) {
            log.error("会话摘要沉淀失败 sessionId={}", sessionId, e);
        }
    }


    private JsonNode parseJsonArray(String answer) {
        if (answer == null) {
            return null;
        }
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(answer.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
