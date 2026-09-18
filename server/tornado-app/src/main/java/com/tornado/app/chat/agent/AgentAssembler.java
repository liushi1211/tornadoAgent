package com.tornado.app.chat.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.tornado.app.chat.interceptor.ToolInvokeInterceptor;
import com.tornado.app.chat.interceptor.UserMessageModelInterceptor;
import com.tornado.app.chat.tool.AgentLocalTools;
import com.tornado.app.chat.tool.CommonTool;
import com.tornado.app.chat.model.ChatModelFactory;
import com.tornado.app.memory.LongTermMemoryService;
import com.tornado.app.rag.RagSearchService;
import com.tornado.domain.chat.util.TokenEstimator;
import com.tornado.domain.skill.gateway.SkillScriptGateway;
import com.tornado.domain.skill.model.Skill;
import com.tornado.domain.skill.repository.SkillRepository;
import com.tornado.infrastructure.chat.saver.SaverRegistry;
import com.tornado.infrastructure.mcp.tool.McpToolFactory;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按请求装配 ReactAgent（详细设计 §4.4）：
 * systemPrompt = 基础人设 + 历史消息 + Skill 摘要清单 + 长期记忆 TopK + 工具说明；
 * tools = 本地 @Tool(read_skill/read_skill_file/run_skill_script/kb_search/delete_uploaded_document)
 *        + 用户 enabled&HEALTHY 的 MCP 工具；
 * hooks = HumanInTheLoopHook.approvalOn(run_skill_script, delete_uploaded_document)；saver 按 threadId 复用（支持 resume）。
 */
@Service
@RequiredArgsConstructor
public class AgentAssembler {

    private static final String BASE_PERSONA = """
            你是 Tornado 智能助手，一个基于 Spring AI Alibaba 的多用户智能体。请用简洁准确的中文回答问题。
            """;

    private final ChatModelFactory chatModelFactory;
    private final SkillRepository skillRepository;
    private final SkillScriptGateway skillScriptGateway;
    private final RagSearchService ragSearchService;
    private final McpToolFactory mcpToolFactory;
    private final LongTermMemoryService longTermMemoryService;
    private final SaverRegistry saverRegistry;
    private final ToolInvokeInterceptor toolInvokeInterceptor;
    private final UserMessageModelInterceptor userMessageModelInterceptor;
    private final CommonTool commonTool;

    @Value("${saa.hitl.enabled:true}")
    private boolean hitlEnabled;

    @Getter
    public static class Assembly {
        private final ReactAgent agent;
        private final String modelId;

        Assembly(ReactAgent agent, String modelId) {
            this.agent = agent;
            this.modelId = modelId;
        }
    }

    /** systemPrompt + 工具集（assemble 与 estimateContextTokens 共用，保证口径一致） */
    private record PromptAndTools(String systemPrompt, List<ToolCallback> tools) {}

    private PromptAndTools buildPromptAndTools(Long uid, String userQuery, String historyText,
                                               boolean useRag, boolean useSkills) {
        StringBuilder sp = new StringBuilder(BASE_PERSONA);
        if (historyText != null && !historyText.isBlank()) {
            sp.append("\n【最近对话历史（短期记忆）】\n").append(historyText);
        }
        List<ToolCallback> tools = new ArrayList<>();
        if (useSkills) {
            List<Skill> skills = skillRepository.listEnabled(uid);
            if (!skills.isEmpty()) {
                sp.append("\n\n【可用技能（命中时用 read_skill 读取全文）】\n\n");
                for (Skill s : skills) {
                    sp.append("- ").append(s.getName()).append(": ").append(s.getDescription()).append("\n\n");
                }
                sp.append("技能可附带脚本：先 read_skill 看说明与资源清单，必要时 read_skill_file 查脚本源码，"
                        + "再 run_skill_script 执行（执行会请求用户批准）。\n");
                tools.addAll(List.of(ToolCallbacks.from(
                        new AgentLocalTools.ReadSkillTool(skillRepository, uid),
                        new AgentLocalTools.ReadSkillFileTool(skillRepository, uid),
                        new AgentLocalTools.RunSkillScriptTool(skillScriptGateway, uid))));
            }
        }
        sp.append(longTermMemoryService.injectTopK(uid, userQuery, longTermMemoryService.injectTopKDefault()));
        if (useRag) {
            tools.addAll(List.of(ToolCallbacks.from(
                    new AgentLocalTools.KbSearchTool(ragSearchService, uid),
                    new AgentLocalTools.DeleteDocumentTool(ragSearchService, uid))));
            sp.append("\n你可以用 kb_search 检索用户知识库；引用检索结果时保留 [doc#seq] 标注；删除文档必须调用 delete_uploaded_document 工具且需要用户批准。\n");
        }
        tools.addAll(mcpToolFactory.toolCallbacks(uid));
        tools.addAll(List.of(ToolCallbacks.from(commonTool)));
        return new PromptAndTools(sp.toString(), tools);
    }

    /** 估算本轮发给模型的上下文 token（systemPrompt + 工具），供占用百分比展示 */
    public int estimateContextTokens(Long uid, String userQuery, String historyText,
                                     boolean useRag, boolean useSkills) {
        PromptAndTools pt = buildPromptAndTools(uid, userQuery, historyText, useRag, useSkills);
        return TokenEstimator.estimate(pt.systemPrompt()) + pt.tools().size() * 80;
    }

    public Assembly assemble(Long uid, String modelId, String userQuery, String historyText,
                             boolean useRag, boolean useSkills, String threadId) {
        String resolvedModel = chatModelFactory.resolveModelId(modelId);
        ChatModel model = chatModelFactory.get(resolvedModel);

        PromptAndTools pt = buildPromptAndTools(uid, userQuery, historyText, useRag, useSkills);
        List<ToolCallback> tools = pt.tools();

        var builder = ReactAgent.builder()
                .name("tornado_agent_" + uid)
                .model(model)
                .systemPrompt(pt.systemPrompt())
                .tools(tools.toArray(new ToolCallback[0]))
                .saver(saverRegistry.forThread(threadId))
                .interceptors(toolInvokeInterceptor,userMessageModelInterceptor)
                .enableLogging(true);
        if (hitlEnabled) {
            Map<String, ToolConfig> approval = new java.util.HashMap<>();
            if (useRag) {
                approval.put("delete_uploaded_document",
                        ToolConfig.builder().description("删除知识库文档为不可逆操作，需人工批准").build());
            }
            if (useSkills) {
                approval.put("run_skill_script",
                        ToolConfig.builder().description("即将在服务器上执行技能脚本，请核对技能名/脚本路径/参数后批准").build());
            }
            if (!approval.isEmpty()) {
                HumanInTheLoopHook hook = HumanInTheLoopHook.builder().approvalOn(approval).build();
                builder = builder.hooks(hook);
            }
        }
        return new Assembly(builder.build(), resolvedModel);
    }
}
