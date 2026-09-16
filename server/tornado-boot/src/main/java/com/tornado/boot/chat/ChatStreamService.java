package com.tornado.boot.chat;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.boot.chat.agent.AgentAssembler;
import com.tornado.boot.chat.hitl.InterruptionStore;
import com.tornado.boot.chat.model.ModelConfigHolder;
import com.tornado.boot.chat.stream.StreamCancelRegistry;
import com.tornado.boot.memory.RedisChatMemoryRepository;
import com.tornado.client.context.UserContext;
import com.tornado.common.entity.ChatMessage;
import com.tornado.common.entity.ChatSession;
import com.tornado.common.entity.HitlRecord;
import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.common.mapper.ChatMessageMapper;
import com.tornado.common.mapper.ChatSessionMapper;
import com.tornado.common.mapper.HitlRecordMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * SSE 流式编排（详细设计 §4.1/§4.2）：agent.stream() 逐帧 NodeOutput → SSE 事件
 * meta/delta/thinking/tool_call/interrupt/done/error；停止经 StreamCancelRegistry；
 * HITL 中断暂存 InterruptionStore + hitl_record(PENDING)，resume 复用同协议续流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    @Data
    public static class ChatReq {
        private Long sessionId;
        private String modelId;
        private String content;
        private String streamId;
        private Boolean useRag = true;
        private Boolean useSkills = true;
        private Boolean enableThinking = false;
    }

    @Data
    public static class HitlResumeReq {
        /** APPROVED|REJECTED|EDITED */
        private String decision;
        private String editedArgsJson;
        private Long sessionId;
        private String modelId;
        private String streamId;
    }

    /** 单轮流式上下文 */
    static final class StreamCtx {
        Long uid;
        Long sessionId;
        String threadId;
        String streamId;
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final List<Map<String, Object>> toolCalls = new ArrayList<>();
        final AtomicBoolean saved = new AtomicBoolean();
        volatile boolean interrupted;
        volatile boolean aborted;
        volatile boolean errored;
        /** 停止信号：dispose 时发射，takeUntilOther 终止整条流（而非仅掐上游），保证 done(ABORTED) 与落库收尾执行 */
        final reactor.core.publisher.Sinks.Empty<Void> cancelSignal = reactor.core.publisher.Sinks.empty();
        String userContent = "";
        boolean resume;
    }

    private final AgentAssembler agentAssembler;
    private final ChatSessionService sessionService;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final HitlRecordMapper hitlRecordMapper;
    private final InterruptionStore interruptionStore;
    private final StreamCancelRegistry cancelRegistry;
    private final ChatMemory chatMemory;
    private final ModelConfigHolder modelHolder;
    private final SaverRegistry saverRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------- 新对话轮 ----------

    public Flux<ServerSentEvent<String>> stream(ChatReq req) {
        Long uid = UserContext.userId();
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "content 不能为空");
        }
        ChatSession session = sessionService.resolveForChat(uid, req.getSessionId(), req.getModelId(), req.getContent());
        StreamCtx ctx = new StreamCtx();
        ctx.uid = uid;
        ctx.sessionId = session.getId();
        ctx.threadId = "s" + session.getId();
        ctx.streamId = resolveStreamId(req.getStreamId());
        ctx.userContent = req.getContent();

        String convId = RedisChatMemoryRepository.convId(uid, session.getId());
        String historyText = renderHistory(convId);

        AgentAssembler.Assembly asm = agentAssembler.assemble(uid, session.getModelId(),
                req.getContent(), historyText, !Boolean.FALSE.equals(req.getUseRag()),
                !Boolean.FALSE.equals(req.getUseSkills()), ctx.threadId);

        persistUserMessage(ctx);

        RunnableConfig config = RunnableConfig.builder().threadId(ctx.threadId).build();
        Flux<NodeOutput> graphFlux;
        try {
            graphFlux = asm.getAgent().stream(req.getContent(), config);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CHAT_MODEL_ERROR, "模型调用发起失败: " + e.getMessage());
        }
        return buildSse(ctx, graphFlux);
    }

    // ---------- HITL resume ----------

    public Flux<ServerSentEvent<String>> resume(String threadId, HitlResumeReq req) {
        Long uid = UserContext.userId();
        InterruptionStore.Stored stored = interruptionStore.take(threadId, uid);
        if (stored == null) {
            throw new BizException(ErrorCode.CHAT_HITL_EXPIRED);
        }
        ChatSession session = sessionService.requireOwned(uid, stored.sessionId());
        StreamCtx ctx = new StreamCtx();
        ctx.uid = uid;
        ctx.sessionId = session.getId();
        ctx.threadId = threadId;
        ctx.streamId = resolveStreamId(req.getStreamId());
        ctx.resume = true;

        AgentAssembler.Assembly asm = agentAssembler.assemble(uid,
                req.getModelId() == null ? session.getModelId() : req.getModelId(),
                "", renderHistory(RedisChatMemoryRepository.convId(uid, session.getId())),
                true, true, threadId);

        // ToolFeedback 三分支（参考 triggerHitl 写法）
        InterruptionMetadata im = stored.metadata();
        InterruptionMetadata.ToolFeedback.FeedbackResult result = switch (
                req.getDecision() == null ? "APPROVED" : req.getDecision().toUpperCase()) {
            case "REJECTED" -> InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED;
            case "EDITED" -> InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED;
            default -> InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED;
        };
        InterruptionMetadata.Builder feedbackBuilder = InterruptionMetadata.builder()
                .nodeId(im.node())
                .state(im.state());
        for (InterruptionMetadata.ToolFeedback tf : im.toolFeedbacks()) {
            InterruptionMetadata.ToolFeedback.Builder tb =
                    InterruptionMetadata.ToolFeedback.builder(tf).result(result);
            if (result == InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED
                    && req.getEditedArgsJson() != null && !req.getEditedArgsJson().isBlank()) {
                tb.arguments(req.getEditedArgsJson());
            }
            feedbackBuilder.addToolFeedback(tb.build());
        }
        RunnableConfig resumeConfig = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedbackBuilder.build())
                .build();
        resolveHitlRecord(threadId, result, req.getEditedArgsJson());

        Flux<NodeOutput> graphFlux;
        try {
            graphFlux = asm.getAgent().stream("", resumeConfig);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CHAT_MODEL_ERROR, "resume 发起失败: " + e.getMessage());
        }
        return buildSse(ctx, graphFlux);
    }

    public void stop(String streamId) {
        cancelRegistry.stop(streamId);
    }

    public List<Map<String, Object>> models() {
        List<Map<String, Object>> out = new ArrayList<>();
        modelHolder.list().forEach(m -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", m.getId());
            item.put("displayName", m.getDisplayName() == null ? m.getId() : m.getDisplayName());
            item.put("provider", m.getProvider());
            item.put("supportsThinking", m.isSupportsThinking());
            out.add(item);
        });
        return out;
    }

    public String defaultModel() {
        return modelHolder.defaultModel();
    }

    // ---------- 共享管线 ----------

    private Flux<ServerSentEvent<String>> buildSse(StreamCtx ctx, Flux<NodeOutput> graphFlux) {
        ServerSentEvent<String> meta = sse("meta", json(Map.of(
                "sessionId", ctx.sessionId, "threadId", ctx.threadId, "streamId", ctx.streamId)));
        Flux<ServerSentEvent<String>> body = graphFlux
                .concatMap(node -> Flux.fromIterable(processNode(ctx, node)))
                .takeUntilOther(ctx.cancelSignal.asMono())
                .concatWith(Flux.defer(() -> {
                    finalizeRound(ctx, ctx.aborted ? "ABORTED" : "STOP");
                    return Flux.just(sse("done", json(Map.of(
                            "finishReason", ctx.aborted ? "ABORTED" : "STOP",
                            "sessionId", ctx.sessionId))));
                }))
                .onErrorResume(e -> {
                    log.error("SSE 流异常 streamId={}", ctx.streamId, e);
                    ctx.errored = true;
                    finalizeRound(ctx, "ERROR");
                    return Flux.just(
                            sse("error", json(Map.of("code", ErrorCode.CHAT_MODEL_ERROR.getCode(),
                                    "message", e.getMessage() == null ? "模型异常" : e.getMessage()))),
                            sse("done", json(Map.of("finishReason", "ERROR", "sessionId", ctx.sessionId))));
                })
                .doOnSubscribe(sub -> cancelRegistry.register(ctx.streamId, () -> {
                    ctx.aborted = true;
                    ctx.cancelSignal.tryEmitEmpty();
                }))
                .doFinally(sig -> {
                    cancelRegistry.remove(ctx.streamId);
                    if (sig == reactor.core.publisher.SignalType.CANCEL) {
                        // 客户端断连等被动取消：兜底释放上游并落库半截内容
                        ctx.aborted = true;
                        ctx.cancelSignal.tryEmitEmpty();
                        finalizeRound(ctx, "ABORTED");
                    }
                });
        return Flux.just(meta).concatWith(body);
    }

    /** 释放本轮 checkpoint（RedisSaver/MemorySaver 均支持），失败只记日志不影响主流程 */
    private void releaseThread(String threadId) {
        try {
            saverRegistry.releaseThread(threadId);
        } catch (Exception e) {
            log.debug("释放 checkpoint 失败 threadId={}: {}", threadId, e.getMessage());
        }
    }

    private List<ServerSentEvent<String>> processNode(StreamCtx ctx, NodeOutput nodeOutput) {
        List<ServerSentEvent<String>> out = new ArrayList<>();
        // ① InterruptionMetadata 本身是 NodeOutput，必须先于 StreamingOutput 判断
        if (nodeOutput instanceof InterruptionMetadata im) {
            ctx.interrupted = true;
            Long hitlId = stashInterrupt(ctx, im);
            InterruptionMetadata.ToolFeedback tf = im.toolFeedbacks().isEmpty()
                    ? null : im.toolFeedbacks().get(0);
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("threadId", ctx.threadId);
            payload.put("hitlId", hitlId);
            payload.put("toolName", tf == null ? "" : tf.getName());
            payload.put("argsJson", tf == null ? "" : tf.getArguments());
            payload.put("reason", tf == null ? "需要人工批准" : tf.getDescription());
            out.add(sse("interrupt", json(payload)));
            return out;
        }
        if (!(nodeOutput instanceof StreamingOutput<?> so)) {
            return out;
        }
        OutputType type = so.getOutputType();
        Message message = so.message();
        if (type == OutputType.AGENT_MODEL_STREAMING && message instanceof AssistantMessage am) {
            Object reasoning = am.getMetadata().get("reasoningContent");
            if (reasoning != null && !reasoning.toString().isBlank()) {
                ctx.thinking.append(reasoning);
                out.add(sse("thinking", json(Map.of("text", reasoning.toString()))));
            } else if (am.getText() != null && !am.getText().isBlank()) {
                ctx.text.append(am.getText());
                out.add(sse("delta", json(Map.of("text", am.getText()))));
            }
        } else if (type == OutputType.AGENT_MODEL_FINISHED && message instanceof AssistantMessage am
                && am.hasToolCalls()) {
            am.getToolCalls().forEach(tc -> {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", tc.name());
                item.put("argsJson", tc.arguments());
                item.put("status", "START");
                ctx.toolCalls.add(item);
                out.add(sse("tool_call", json(item)));
            });
        } else if (type == OutputType.AGENT_TOOL_FINISHED && message instanceof ToolResponseMessage trm) {
            trm.getResponses().forEach(r -> {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", r.name());
                item.put("status", "END");
                item.put("resultDigest", digest(r.responseData()));
                ctx.toolCalls.add(item);
                out.add(sse("tool_call", json(item)));
            });
        }
        return out;
    }

    private Long stashInterrupt(StreamCtx ctx, InterruptionMetadata im) {
        interruptionStore.put(ctx.threadId, ctx.uid, ctx.sessionId, im);
        InterruptionMetadata.ToolFeedback tf = im.toolFeedbacks().isEmpty()
                ? null : im.toolFeedbacks().get(0);
        HitlRecord exist = hitlRecordMapper.selectOne(new LambdaQueryWrapper<HitlRecord>()
                .eq(HitlRecord::getThreadId, ctx.threadId));
        HitlRecord rec = exist != null ? exist : new HitlRecord();
        rec.setUserId(ctx.uid);
        rec.setSessionId(ctx.sessionId);
        rec.setThreadId(ctx.threadId);
        rec.setToolName(tf == null ? "" : tf.getName());
        rec.setArgsJson(tf == null ? null : tf.getArguments());
        rec.setDecision(null);
        rec.setEditedArgsJson(null);
        rec.setStatus("PENDING");
        rec.setDecidedAt(null);
        if (exist == null) {
            hitlRecordMapper.insert(rec);
        } else {
            hitlRecordMapper.updateById(rec);
        }
        return rec.getId();
    }

    private void resolveHitlRecord(String threadId,
                                   InterruptionMetadata.ToolFeedback.FeedbackResult result,
                                   String editedArgsJson) {
        HitlRecord rec = hitlRecordMapper.selectOne(new LambdaQueryWrapper<HitlRecord>()
                .eq(HitlRecord::getThreadId, threadId));
        if (rec != null) {
            rec.setStatus("RESOLVED");
            rec.setDecision(result.name());
            rec.setEditedArgsJson(editedArgsJson);
            rec.setDecidedAt(LocalDateTime.now());
            hitlRecordMapper.updateById(rec);
        }
    }

    private synchronized void finalizeRound(StreamCtx ctx, String finishReason) {
        if (!ctx.saved.compareAndSet(false, true)) {
            return;
        }
        try {
            String text = ctx.text.toString();
            if (ctx.aborted || "ABORTED".equals(finishReason)) {
                finishReason = "ABORTED";
            }
            if (text.isEmpty() && ctx.thinking.isEmpty() && ctx.toolCalls.isEmpty()) {
                return;
            }
            ChatMessage msg = new ChatMessage();
            msg.setSessionId(ctx.sessionId);
            msg.setUserId(ctx.uid);
            msg.setRole(MessageType.ASSISTANT.getValue());
            msg.setContent(text);
            msg.setThinking(ctx.thinking.toString());
            msg.setToolCalls(ctx.toolCalls.isEmpty() ? null : objectMapper.writeValueAsString(ctx.toolCalls));
            msg.setFinishReason(finishReason);
            msg.setPromptTokens(0);
            msg.setCompletionTokens(0);
            messageMapper.insert(msg);
            // 短期记忆追加（中断的半截内容也入记忆，标注[已中断]，保持上下文真实）
            String convId = RedisChatMemoryRepository.convId(ctx.uid, ctx.sessionId);
            List<Message> append = new ArrayList<>();
            if (!ctx.resume && !ctx.userContent.isEmpty()) {
                append.add(new org.springframework.ai.chat.messages.UserMessage(ctx.userContent));
            }
            append.add(new AssistantMessage(text + ("ABORTED".equals(finishReason) ? "[已中断]" : "")));
            chatMemory.add(convId, append);
        } catch (Exception e) {
            log.error("回合落库失败 sessionId={}", ctx.sessionId, e);
        }
    }

    private void persistUserMessage(StreamCtx ctx) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(ctx.sessionId);
        msg.setUserId(ctx.uid);
        msg.setRole(MessageType.USER.getValue());
        msg.setContent(ctx.userContent);
        msg.setPromptTokens(0);
        msg.setCompletionTokens(0);
        messageMapper.insert(msg);
        // 更新会话标题（默认标题时用首条消息截断）
        ChatSession s = sessionMapper.selectById(ctx.sessionId);
        if (s != null && "新会话".equals(s.getTitle())) {
            s.setTitle(ctx.userContent.length() > 20 ? ctx.userContent.substring(0, 20) : ctx.userContent);
            sessionMapper.updateById(s);
        }
    }

    private String renderHistory(String convId) {
        try {
            List<Message> history = chatMemory.get(convId);
            if (history.isEmpty()) {
                return "";
            }
            return history.stream()
                    .map(m -> m.getMessageType().getValue() + ": " + m.getText())
                    .collect(Collectors.joining("\n")) + "\n";
        } catch (Exception e) {
            log.warn("短期记忆读取失败（忽略）: {}", e.getMessage());
            return "";
        }
    }

    private String resolveStreamId(String streamId) {
        return streamId == null || streamId.isBlank()
                ? java.util.UUID.randomUUID().toString() : streamId;
    }

    private String digest(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"serialize\"}";
        }
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
