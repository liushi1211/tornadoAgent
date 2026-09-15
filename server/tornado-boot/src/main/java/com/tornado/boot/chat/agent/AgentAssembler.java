package com.tornado.boot.chat.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.tornado.boot.Interceptors.ToolInvokeInterceptor;
import com.tornado.boot.Interceptors.UserMessageModelInterceptor;
import com.tornado.boot.chat.SaverRegistry;
import com.tornado.boot.chat.model.ChatModelFactory;
import com.tornado.boot.mcp.McpToolFactory;
import com.tornado.boot.memory.LongTermMemoryService;
import com.tornado.boot.memory.MemoryConfig;
import com.tornado.boot.rag.RagSearchService;
import com.tornado.boot.skill.SkillService;
import com.tornado.boot.tool.AgentLocalTools;
import com.tornado.common.entity.Skill;
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
    private final SkillService skillService;
    private final RagSearchService ragSearchService;
    private final McpToolFactory mcpToolFactory;
    private final LongTermMemoryService longTermMemoryService;
    private final MemoryConfig memoryConfig;
    private final SaverRegistry saverRegistry;
    private final ToolInvokeInterceptor toolInvokeInterceptor;
    private final UserMessageModelInterceptor userMessageModelInterceptor;

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

    public Assembly assemble(Long uid, String modelId, String userQuery, String historyText,
                             boolean useRag, boolean useSkills, String threadId) {
        String resolvedModel = chatModelFactory.resolveModelId(modelId);
        ChatModel model = chatModelFactory.get(resolvedModel);

        StringBuilder sp = new StringBuilder(BASE_PERSONA);
        if (historyText != null && !historyText.isBlank()) {
            sp.append("\n【最近对话历史（短期记忆）】\n").append(historyText);
        }
        List<ToolCallback> tools = new ArrayList<>();
        if (useSkills) {
            List<Skill> skills = skillService.enabledSkills(uid);
            if (!skills.isEmpty()) {
                sp.append("\n【可用技能（命中时用 read_skill 读取全文）】\n");
                for (Skill s : skills) {
                    sp.append("- ").append(s.getName()).append(": ").append(s.getDescription()).append('\n');
                }
                sp.append("技能可附带脚本：先 read_skill 看说明与资源清单，必要时 read_skill_file 查脚本源码，"
                        + "再 run_skill_script 执行（执行会请求用户批准）。\n");
                tools.addAll(List.of(ToolCallbacks.from(
                        new AgentLocalTools.ReadSkillTool(skillService, uid),
                        new AgentLocalTools.ReadSkillFileTool(skillService, uid),
                        new AgentLocalTools.RunSkillScriptTool(skillService, uid))));
            }
        }
        sp.append(longTermMemoryService.injectTopK(uid, userQuery, memoryConfig.getInjectTopk()));
        if (useRag) {
            tools.addAll(List.of(ToolCallbacks.from(
                    new AgentLocalTools.KbSearchTool(ragSearchService, uid),
                    new AgentLocalTools.DeleteDocumentTool(ragSearchService, uid))));
            sp.append("\n你可以用 kb_search 检索用户知识库；引用检索结果时保留 [doc#seq] 标注；删除文档必须调用 delete_uploaded_document 工具且需要用户批准。\n");
        }
        tools.addAll(mcpToolFactory.toolCallbacks(uid));

        var builder = ReactAgent.builder()
                .name("tornado_agent_" + uid)
                .model(model)
                .systemPrompt(sp.toString())
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
