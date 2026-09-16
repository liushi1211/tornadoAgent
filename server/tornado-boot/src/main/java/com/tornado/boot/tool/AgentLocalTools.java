package com.tornado.boot.tool;

import com.tornado.app.rag.RagSearchService;
import com.tornado.domain.skill.gateway.SkillScriptGateway;
import com.tornado.domain.skill.model.Skill;
import com.tornado.domain.skill.model.SkillFile;
import com.tornado.domain.skill.repository.SkillRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 会话级本地工具（每请求 new，绑定 userId，天然租户隔离；不作为 Bean 注册）。
 * 技能类工具依赖 domain 的 SkillRepository/SkillScriptGateway（沙箱/进程细节封装在 infrastructure）。
 */
public final class AgentLocalTools {

    private AgentLocalTools() {}

    /** read_skill：渐进披露，命中技能后读取 SKILL.md 全文 + 资源清单 */
    public static class ReadSkillTool {
        private final SkillRepository skillRepository;
        private final Long uid;

        public ReadSkillTool(SkillRepository skillRepository, Long uid) {
            this.skillRepository = skillRepository;
            this.uid = uid;
        }

        @Tool(name = "read_skill", description = "按技能名读取该技能 SKILL.md 全文与资源文件清单，用于渐进式加载技能说明")
        public String readSkill(@ToolParam(description = "技能 name（小写字母数字与连字符）") String name) {
            Skill s = skillRepository.findByUserAndName(uid, name);
            if (s == null) {
                return "未找到技能: " + name;
            }
            StringBuilder sb = new StringBuilder("# ").append(s.getName())
                    .append("\n").append(s.getDescription()).append("\n\n").append(s.getContentMd());
            List<SkillFile> fs = skillRepository.files(s.getId());
            if (!fs.isEmpty()) {
                sb.append("\n\n【技能包内资源文件】\n");
                for (SkillFile f : fs) {
                    sb.append("- ").append(f.getRelPath())
                            .append("  (").append(f.getFileType()).append(", ").append(f.getSizeBytes()).append("B)\n");
                }
                sb.append("脚本可先用 read_skill_file 查看内容，再用 run_skill_script 执行（执行需用户批准）。\n");
            }
            return sb.toString();
        }
    }

    /** read_skill_file：读取技能包内某个资源文件内容 */
    public static class ReadSkillFileTool {
        private final SkillRepository skillRepository;
        private final Long uid;

        public ReadSkillFileTool(SkillRepository skillRepository, Long uid) {
            this.skillRepository = skillRepository;
            this.uid = uid;
        }

        @Tool(name = "read_skill_file", description = "读取技能包内指定资源文件的文本内容（如脚本源码），path 来自 read_skill 的资源清单")
        public String read(@ToolParam(description = "技能 name") String name,
                           @ToolParam(description = "资源相对路径，如 scripts/count.js") String path) {
            Skill s = skillRepository.findByUserAndName(uid, name);
            if (s == null) {
                return "未找到技能: " + name;
            }
            SkillFile f = skillRepository.file(s.getId(), path == null ? "" : path.trim());
            return f == null ? "未找到资源文件: " + path : f.getContent();
        }
    }

    /** run_skill_script：执行技能包内脚本（委托 infra 沙箱网关；必须经 HumanInTheLoopHook 人工批准） */
    public static class RunSkillScriptTool {
        private final SkillScriptGateway skillScriptGateway;
        private final Long uid;

        public RunSkillScriptTool(SkillScriptGateway skillScriptGateway, Long uid) {
            this.skillScriptGateway = skillScriptGateway;
            this.uid = uid;
        }

        @Tool(name = "run_skill_script", description = "在沙箱目录执行技能包内的脚本资源并返回输出。"
                + "scriptPath 必须是 read_skill 清单中的相对路径；args 为空格分隔的命令行参数（可空）；执行属于服务端命令，需用户批准")
        public String run(@ToolParam(description = "技能 name") String name,
                          @ToolParam(description = "脚本相对路径，如 scripts/count.js") String scriptPath,
                          @ToolParam(description = "命令行参数，空格分隔；含空格的单个参数（如一段文本）必须用双引号整体包裹，可传空字符串") String args) {
            return skillScriptGateway.run(uid, name, scriptPath, args);
        }
    }

    /** kb_search：知识库检索工具，返回带 [doc#seq] 引用标注的上下文 */
    public static class KbSearchTool {
        private final RagSearchService ragSearchService;
        private final Long uid;

        public KbSearchTool(RagSearchService ragSearchService, Long uid) {
            this.ragSearchService = ragSearchService;
            this.uid = uid;
        }

        @Tool(name = "kb_search", description = "查询关于个人的信息时，无论任何个人信息，比如 我叫什么，我的姓名，我的年龄，我在哪工作等等,都使用此工具检索与问题相关的资料片段，回答含引用标注")
        public String kbSearch(@ToolParam(description = "检索查询语句") String query) {
            return ragSearchService.searchContext(uid, query, 5);
        }
    }

    /** delete_uploaded_document：敏感写操作，挂 HumanInTheLoopHook 需人工批准 */
    public static class DeleteDocumentTool {
        private final RagSearchService ragSearchService;
        private final Long uid;

        public DeleteDocumentTool(RagSearchService ragSearchService, Long uid) {
            this.ragSearchService = ragSearchService;
            this.uid = uid;
        }

        @Tool(name = "delete_uploaded_document", description = "删除用户知识库中的指定文档（不可逆，需人工批准）")
        public String deleteDocument(@ToolParam(description = "文档 id（数字）") String docId) {
            try {
                return ragSearchService.deleteDocument(uid, Long.parseLong(docId.trim()));
            } catch (NumberFormatException e) {
                return "docId 需为数字，收到: " + docId;
            }
        }
    }
}
