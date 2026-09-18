package com.tornado.app.chat;

import com.tornado.app.chat.agent.AgentAssembler;
import com.tornado.app.chat.model.ChatModelFactory;
import com.tornado.client.chat.dto.ContextUsageDTO;
import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.domain.chat.gateway.ContextUsageGateway;
import com.tornado.domain.chat.model.ChatSession;
import com.tornado.domain.chat.model.ContextUsage;
import com.tornado.domain.chat.repository.ChatSessionRepository;
import com.tornado.domain.memory.gateway.ChatMemoryGateway;
import com.tornado.domain.memory.model.ChatMessageItem;
import com.tornado.domain.modelconfig.ModelCatalog;
import com.tornado.domain.modelconfig.model.ModelDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话上下文应用服务：估算/查询上下文占用，并按「摘要早期 + 保留最近」压缩短期记忆。
 * 压缩只改写短期记忆窗口（Redis），原始 chat_message 仍留库用于历史展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatContextService {

    private static final int KEEP_RECENT = 6;          // 压缩后保留的最近消息条数
    private static final int MIN_FOR_COMPRESS = 8;     // 少于该条数不值得压缩
    private static final int TRANSCRIPT_MAX_CHARS = 8000;
    private static final String SUMMARY_PROMPT = """
            你是对话压缩助手。请把下面的历史对话压缩成简洁中文要点摘要，保留关键事实、决定、待办与用户偏好，
            去掉寒暄与冗余，不超过 300 字。直接输出摘要正文，不要加标题或解释。
            """;

    private final ChatSessionRepository sessionRepository;
    private final ModelCatalog modelCatalog;
    private final ContextUsageGateway contextUsageGateway;
    private final ChatMemoryGateway chatMemoryGateway;
    private final ChatModelFactory chatModelFactory;
    private final AgentAssembler agentAssembler;

    public ContextUsageDTO usage(Long uid, Long sessionId) {
        ChatSession session = requireOwned(uid, sessionId);
        ContextUsage cu = contextUsageGateway.load(uid, sessionId);
        String modelId = cu != null && cu.modelId() != null ? cu.modelId() : session.getModelId();
        int window = windowOf(modelId);
        int used = cu == null ? 0 : cu.usedTokens();
        return toDTO(modelId, used, window);
    }

    public ContextUsageDTO compress(Long uid, Long sessionId) {
        ChatSession session = requireOwned(uid, sessionId);
        List<ChatMessageItem> memory = chatMemoryGateway.load(uid, sessionId);
        if (memory.size() <= Math.max(KEEP_RECENT, MIN_FOR_COMPRESS)) {
            return usage(uid, sessionId); // 还不需要压缩
        }
        List<ChatMessageItem> older = new ArrayList<>(memory.subList(0, memory.size() - KEEP_RECENT));
        List<ChatMessageItem> recent = new ArrayList<>(memory.subList(memory.size() - KEEP_RECENT, memory.size()));

        String transcript = truncate(older.stream()
                .map(m -> m.role() + ": " + m.text())
                .collect(Collectors.joining("\n")), TRANSCRIPT_MAX_CHARS);

        String summary;
        try {
            ChatModel model = chatModelFactory.get(session.getModelId());
            summary = model.call(new Prompt(List.of(
                    new SystemMessage(SUMMARY_PROMPT), new UserMessage(transcript))))
                    .getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("上下文压缩摘要失败 sessionId={}: {}", sessionId, e.getMessage());
            throw new BizException(ErrorCode.CHAT_MODEL_ERROR, "压缩失败：" + e.getMessage());
        }

        List<ChatMessageItem> compressed = new ArrayList<>();
        compressed.add(new ChatMessageItem(ChatMessageItem.ROLE_SYSTEM, "【历史对话摘要，供参考】\n" + safe(summary)));
        compressed.addAll(recent);
        chatMemoryGateway.clear(uid, sessionId);
        chatMemoryGateway.append(uid, sessionId, compressed);

        // 重算并落快照（用压缩后的历史估算；RAG/Skills 按默认开启口径）
        String historyText = render(compressed);
        int used = agentAssembler.estimateContextTokens(uid, "", historyText, true, true);
        contextUsageGateway.save(uid, sessionId,
                new ContextUsage(session.getModelId(), used, System.currentTimeMillis()));
        log.info("会话 {} 上下文已压缩：{} 条 → {} 条，估算 token={}", sessionId, memory.size(), compressed.size(), used);
        return usage(uid, sessionId);
    }

    private int windowOf(String modelId) {
        ModelDefinition md = modelCatalog.findOrDefault(modelId);
        return md == null ? 128000 : md.contextWindowOrDefault();
    }

    private ContextUsageDTO toDTO(String modelId, int used, int window) {
        ContextUsageDTO d = new ContextUsageDTO();
        d.setModelId(modelId);
        d.setUsedTokens(used);
        d.setContextWindow(window);
        int pct = window > 0 ? (int) Math.round(used * 100.0 / window) : 0;
        d.setPercent(Math.max(0, Math.min(100, pct)));
        return d;
    }

    private ChatSession requireOwned(Long uid, Long sessionId) {
        ChatSession s = sessionRepository.findById(sessionId);
        if (s == null || !s.getUserId().equals(uid)) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    private static String render(List<ChatMessageItem> items) {
        if (items.isEmpty()) {
            return "";
        }
        return items.stream().map(m -> m.role() + ": " + m.text()).collect(Collectors.joining("\n")) + "\n";
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
